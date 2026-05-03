package com.beworking.auth;

import com.beworking.contacts.ContactProfile;
import com.beworking.contacts.ContactProfileRepository;
import com.beworking.contacts.ViesVatService;
import com.beworking.plans.PlanRepository;
import com.beworking.subscriptions.Subscription;
import com.beworking.subscriptions.SubscriptionRepository;
import com.beworking.subscriptions.SubscriptionService;
import com.beworking.subscriptions.SubscriptionInvoicePayload;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

/**
 * Handles self-service user registration, confirmation, and password reset flows.
 */
@Service
public class RegisterService {
    private static final Logger logger = LoggerFactory.getLogger(RegisterService.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final EmailService emailService;
    private final ContactProfileRepository contactProfileRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final PlanRepository planRepository;
    private final ViesVatService viesVatService;
    private final SubscriptionService subscriptionService;

    public RegisterService(UserRepository userRepository, PasswordEncoder passwordEncoder,
                           EmailService emailService, ContactProfileRepository contactProfileRepository,
                           SubscriptionRepository subscriptionRepository, PlanRepository planRepository,
                           ViesVatService viesVatService, SubscriptionService subscriptionService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.emailService = emailService;
        this.contactProfileRepository = contactProfileRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.planRepository = planRepository;
        this.viesVatService = viesVatService;
        this.subscriptionService = subscriptionService;
    }

    /**
     * Creates a User + ContactProfile in pending-payment state BEFORE Stripe customer creation.
     * Called by /auth/register-pending at the start of the signup flow so we always have a DB
     * record that can be reconciled with Stripe (or recovered if signup is abandoned).
     *
     * Idempotent: if a pending user with this email already exists, fields are updated.
     * Throws IllegalStateException if an ACTIVE (emailConfirmed) user with this email exists.
     */
    @Transactional
    public User registerPendingUser(RegisterRequest request) {
        String name = request.getName();
        String email = request.getEmail();
        String password = request.getPassword();

        if (!isNonBlank(name) || !isNonBlank(email) || !isPasswordValid(password)) {
            throw new IllegalArgumentException("Invalid data: name, email and password (8-64 chars) are required");
        }

        String normalizedEmail = email.toLowerCase().trim();
        var existingUser = userRepository.findByEmail(normalizedEmail);

        if (existingUser.isPresent() && existingUser.get().isEmailConfirmed()) {
            throw new IllegalStateException("User already exists");
        }

        User user;
        if (existingUser.isPresent()) {
            // Pending user retry — refresh fields with latest input
            user = existingUser.get();
            user.setPassword(passwordEncoder.encode(password));
            user.setName(name.trim());
            user.setPhone(request.getPhone());
        } else {
            user = new User(normalizedEmail, passwordEncoder.encode(password), User.Role.USER);
            user.setName(name.trim());
            user.setPhone(request.getPhone());
            user.setEmailConfirmed(false); // pending until payment completes
        }

        // Auto-link or create ContactProfile in pending state
        ContactProfile cp = applyContactProfileFromRequest(user, request, normalizedEmail, "Pendiente Pago");

        contactProfileRepository.save(cp);
        userRepository.save(user);

        logger.info("Created pending user (no Stripe yet) email={} cp={}", normalizedEmail, cp.getId());
        return user;
    }

    /**
     * Creates a new user account with a trial subscription when inputs are valid.
     * If a pending user already exists for this email (created via registerPendingUser),
     * the user is finalized: emailConfirmed flips to true, ContactProfile.status -> "Activo",
     * and the subscription is provisioned.
     * Otherwise (legacy direct signup), a fresh user is created and finalized atomically.
     * Throws IllegalStateException if an active user already exists.
     */
    @Transactional
    public User registerUserWithTrial(RegisterRequest request) {
        String name = request.getName();
        String email = request.getEmail();
        String password = request.getPassword();

        if (!isNonBlank(name) || !isNonBlank(email) || !isPasswordValid(password)) {
            throw new IllegalArgumentException("Invalid data: name, email and password (8-64 chars) are required");
        }

        String normalizedEmail = email.toLowerCase().trim();
        var existingUser = userRepository.findByEmail(normalizedEmail);

        User user;
        if (existingUser.isPresent() && existingUser.get().isEmailConfirmed()) {
            throw new IllegalStateException("User already exists");
        } else if (existingUser.isPresent()) {
            // Pending user from /auth/register-pending — finalize
            user = existingUser.get();
            user.setName(name.trim());
            user.setPhone(request.getPhone());
            user.setEmailConfirmed(true);
            if (request.getStripeCustomerId() != null && !request.getStripeCustomerId().isBlank()) {
                user.setStripeCustomerId(request.getStripeCustomerId());
            }
        } else {
            // Legacy direct signup — create user from scratch
            user = new User(normalizedEmail, passwordEncoder.encode(password), User.Role.USER);
            user.setName(name.trim());
            user.setPhone(request.getPhone());
            user.setEmailConfirmed(true); // Auto-confirm: payment method already proves identity
            user.setStripeCustomerId(request.getStripeCustomerId());
        }

        ContactProfile cp = applyContactProfileFromRequest(user, request, normalizedEmail, "Activo");
        contactProfileRepository.save(cp);
        userRepository.save(user);

        // Create subscription via /api/subscriptions/auto (card already on file from SetupIntent)
        if (request.getPlan() != null && !request.getPlan().isBlank()) {
            String planKey = request.getPlan().toLowerCase();
            if ("basis".equals(planKey)) planKey = "basic";

            var planOpt = planRepository.findByPlanKey(planKey);
            java.math.BigDecimal amount = planOpt.map(com.beworking.plans.Plan::getPrice)
                    .orElse(new java.math.BigDecimal("15.00"));
            String planLabel = planOpt.map(com.beworking.plans.Plan::getName)
                    .orElse(planKey.substring(0, 1).toUpperCase() + planKey.substring(1));

            // Calculate VAT using VIES validation
            String vatNumber = request.getTaxId();
            int vatPercent = 21; // Default: Spanish IVA
            boolean taxExempt = false;
            if (vatNumber != null && !vatNumber.isBlank()) {
                // Try VIES validation with country hint from billing country
                String countryHint = com.beworking.subscriptions.SubscriptionService.countryNameToIso(
                    cp.getBillingCountry());
                var viesResult = viesVatService.validate(vatNumber, countryHint);
                if (viesResult.valid()) {
                    // Valid EU VAT — check if intra-community (not ES supplier)
                    String normalized = vatNumber.trim().toUpperCase().replaceAll("\\s+", "");
                    String prefix = normalized.length() >= 2 ? normalized.substring(0, 2) : "";
                    if (!prefix.equals("ES")) {
                        vatPercent = 0;
                        taxExempt = true;
                    }
                    logger.info("VIES validated {} — taxExempt={}", vatNumber, taxExempt);
                } else {
                    logger.info("VIES could not validate {} — applying default 21% VAT", vatNumber);
                }
            }

            // Send BASE amount — Stripe applies tax rate automatically
            int baseAmountCents = amount.multiply(java.math.BigDecimal.valueOf(100)).intValue();

            // Customer already has card from SetupIntent — charge_automatically charges immediately
            String customerId = request.getStripeCustomerId();
            var stripeResult = createAutoSubscription(
                normalizedEmail, name.trim(), baseAmountCents, "BeWorking " + planLabel,
                customerId, taxExempt
            );

            Subscription sub = new Subscription();
            sub.setContactId(cp.getId());
            sub.setMonthlyAmount(amount);
            sub.setCurrency(planOpt.map(com.beworking.plans.Plan::getCurrency).orElse("EUR"));
            sub.setDescription("BeWorking " + planLabel);
            sub.setBillingMethod("stripe");
            sub.setCuenta("PT");
            sub.setStartDate(LocalDate.now());
            sub.setEndDate(null);
            sub.setActive(true); // Active immediately — card charged on subscription creation
            sub.setCreatedAt(LocalDateTime.now());
            sub.setVatNumber(vatNumber);
            sub.setVatPercent(vatPercent);

            if (stripeResult == null) {
                throw new IllegalStateException("Payment failed. Please try again.");
            }
            sub.setStripeSubscriptionId((String) stripeResult.get("subscriptionId"));
            sub.setStripeCustomerId((String) stripeResult.get("customerId"));
            user.setStripeCustomerId((String) stripeResult.get("customerId"));
            userRepository.save(user);
            subscriptionRepository.save(sub);

            // Create local invoice from Stripe first invoice data
            @SuppressWarnings("unchecked")
            Map<String, Object> firstInvoice = (Map<String, Object>) stripeResult.get("firstInvoice");
            if (firstInvoice != null) {
                try {
                    SubscriptionInvoicePayload inv = new SubscriptionInvoicePayload();
                    inv.setStripeSubscriptionId(sub.getStripeSubscriptionId());
                    inv.setStripeInvoiceId((String) firstInvoice.get("stripeInvoiceId"));
                    inv.setStripePaymentIntentId((String) firstInvoice.get("paymentIntentId"));
                    inv.setSubtotalCents(firstInvoice.get("subtotalCents") != null ? ((Number) firstInvoice.get("subtotalCents")).intValue() : 0);
                    inv.setTaxCents(firstInvoice.get("taxCents") != null ? ((Number) firstInvoice.get("taxCents")).intValue() : 0);
                    inv.setInvoicePdf((String) firstInvoice.get("invoicePdf"));
                    inv.setPeriodStart((String) firstInvoice.get("periodStart"));
                    inv.setPeriodEnd((String) firstInvoice.get("periodEnd"));
                    inv.setStatus("paid");
                    subscriptionService.createInvoiceFromSubscription(sub, inv);
                    logger.info("Created local invoice for subscription {}", sub.getStripeSubscriptionId());
                } catch (Exception e) {
                    logger.warn("Failed to create local invoice: {}", e.getMessage());
                }
            }
        }

        // Send admin notification email
        emailService.sendRegistrationAdminNotification(
            name, normalizedEmail, request.getPhone(),
            request.getCompany(), request.getTaxId(),
            request.getPlan(), request.getLocation()
        );

        // Send welcome email to the user
        String planLabel = request.getPlan() != null ? request.getPlan() : "Basic";
        emailService.sendSubscriptionWelcomeEmail(normalizedEmail, name.trim(), planLabel, request.getLocation());

        return user;
    }

    /**
     * Creates a free user account with email confirmation required.
     * No payment, no Stripe, no subscription. User must confirm email before login.
     */
    @Transactional
    public User registerSimple(RegisterRequest request) {
        String name = request.getName();
        String email = request.getEmail();
        String password = request.getPassword();

        if (!isNonBlank(name) || !isNonBlank(email) || !isPasswordValid(password)) {
            return null;
        }

        String normalizedEmail = email.toLowerCase().trim();

        if (userRepository.findByEmail(normalizedEmail).isPresent()) {
            return null;
        }

        User user = new User(normalizedEmail, passwordEncoder.encode(password), User.Role.USER);
        user.setName(name.trim());
        user.setPhone(request.getPhone());
        user.setEmailConfirmed(false); // Requires email confirmation

        // Generate confirmation token
        String rawToken = UUID.randomUUID().toString();
        user.setConfirmationToken(hashToken(rawToken));
        user.setConfirmationTokenExpiry(Instant.now().plus(1, ChronoUnit.HOURS));

        // Auto-link or create ContactProfile
        var existingProfile = contactProfileRepository
            .findFirstByEmailPrimaryIgnoreCaseOrEmailSecondaryIgnoreCaseOrEmailTertiaryIgnoreCaseOrRepresentativeEmailIgnoreCase(
                normalizedEmail, normalizedEmail, normalizedEmail, normalizedEmail);

        ContactProfile cp;
        if (existingProfile.isPresent()) {
            cp = existingProfile.get();
            user.setTenantId(cp.getId());
        } else {
            cp = new ContactProfile();
            cp.setId(System.currentTimeMillis());
            cp.setName(name.trim());
            cp.setEmailPrimary(normalizedEmail);
            cp.setStatus("Activo");
            cp.setTenantType("Usuario Free");
            cp.setActive(true);
            cp.setCreatedAt(LocalDateTime.now());
            cp.setStatusChangedAt(LocalDateTime.now());
            cp.setChannel("Self-registration-free");
            user.setTenantId(cp.getId());
        }

        if (request.getCompany() != null && !request.getCompany().isBlank()) {
            cp.setBillingName(request.getCompany().trim());
        }
        if (request.getPhone() != null && !request.getPhone().isBlank()) {
            cp.setPhonePrimary(request.getPhone().trim());
        }

        contactProfileRepository.save(cp);
        userRepository.save(user);

        // Send confirmation email
        emailService.sendConfirmationEmail(normalizedEmail, rawToken);

        // Send admin notification
        emailService.sendRegistrationAdminNotification(
            name, normalizedEmail, request.getPhone(),
            request.getCompany(), null, null, null
        );

        return user;
    }

    /**
     * Retrieves a user awaiting confirmation by a one-time token.
     */
    public java.util.Optional<User> findByConfirmationToken(String token) {
        return token == null ? java.util.Optional.empty() : userRepository.findByConfirmationToken(hashToken(token));
    }

    public void saveUser(User user) {
        userRepository.save(user);
    }

    public boolean sendPasswordResetEmail(String email) {
        if (email != null) {
            email = email.toLowerCase().trim();
        }
        var userOpt = userRepository.findByEmail(email);
        if (userOpt.isEmpty()) {
            return false;
        }
        User user = userOpt.get();
        String token = UUID.randomUUID().toString();
        user.setConfirmationToken(hashToken(token));
        user.setConfirmationTokenExpiry(Instant.now().plus(1, ChronoUnit.HOURS));
        userRepository.save(user);
        emailService.sendPasswordResetEmail(email, token);
        return true;
    }

    /**
     * Resets a password when the token is valid, unexpired, and meets complexity.
     */
    public boolean resetPassword(String token, String newPassword) {
        if (!isPasswordValid(newPassword)) {
            return false;
        }
        var userOpt = token == null ? java.util.Optional.<User>empty() : userRepository.findByConfirmationToken(hashToken(token));
        if (userOpt.isEmpty()) {
            return false;
        }
        User user = userOpt.get();
        if (user.getConfirmationTokenExpiry() == null || user.getConfirmationTokenExpiry().isBefore(Instant.now())) {
            return false;
        }
        String hashedPassword = passwordEncoder.encode(newPassword);
        user.setPassword(hashedPassword);
        user.setConfirmationToken(null);
        user.setConfirmationTokenExpiry(null);
        userRepository.save(user);
        return true;
    }

    /**
     * Creates a user account for an admin-created contact and sends a welcome email.
     * Returns the existing user if one already exists for this email.
     */
    public User createUserForContact(String email, String name, Long contactProfileId) {
        return createUserForContactInternal(email, name, contactProfileId, true);
    }

    /**
     * Creates a user account for an existing contact without sending email.
     * Used for bulk migration of contacts that already exist.
     */
    public User createUserForContactSilent(String email, String name, Long contactProfileId) {
        return createUserForContactInternal(email, name, contactProfileId, false);
    }

    private User createUserForContactInternal(String email, String name, Long contactProfileId, boolean sendEmail) {
        if (email == null || email.isBlank()) {
            return null;
        }

        String normalizedEmail = email.toLowerCase().trim();

        // If user already exists, ensure linkage and return
        var existing = userRepository.findByEmail(normalizedEmail);
        if (existing.isPresent()) {
            User user = existing.get();
            if (user.getTenantId() == null) {
                user.setTenantId(contactProfileId);
                userRepository.save(user);
            }
            return user;
        }

        // Create user with random password (user will set their own via welcome email)
        String randomPassword = UUID.randomUUID().toString() + UUID.randomUUID().toString();
        User user = new User(normalizedEmail, passwordEncoder.encode(randomPassword), User.Role.USER);
        user.setName(name != null ? name.trim() : null);
        user.setEmailConfirmed(true);
        user.setTenantId(contactProfileId);

        if (sendEmail) {
            // Generate token for the welcome email password-set link
            String rawToken = UUID.randomUUID().toString();
            user.setConfirmationToken(hashToken(rawToken));
            user.setConfirmationTokenExpiry(Instant.now().plus(24, ChronoUnit.HOURS));
            userRepository.save(user);
            emailService.sendWelcomeEmail(normalizedEmail, rawToken);
        } else {
            userRepository.save(user);
        }

        return user;
    }

    /**
     * Creates a user account for a booking contact and sends a booking welcome email.
     * Idempotent: returns existing user if one already exists for this email.
     * Token expires in 48 hours.
     */
    public User createUserForBookingContact(String email, String name, Long contactProfileId) {
        if (email == null || email.isBlank()) {
            return null;
        }

        String normalizedEmail = email.toLowerCase().trim();

        var existing = userRepository.findByEmail(normalizedEmail);
        if (existing.isPresent()) {
            User user = existing.get();
            if (user.getTenantId() == null) {
                user.setTenantId(contactProfileId);
                userRepository.save(user);
            }
            return user;
        }

        String randomPassword = UUID.randomUUID().toString() + UUID.randomUUID().toString();
        User user = new User(normalizedEmail, passwordEncoder.encode(randomPassword), User.Role.USER);
        user.setName(name != null ? name.trim() : null);
        user.setEmailConfirmed(true);
        user.setTenantId(contactProfileId);

        String rawToken = UUID.randomUUID().toString();
        user.setConfirmationToken(hashToken(rawToken));
        user.setConfirmationTokenExpiry(Instant.now().plus(48, ChronoUnit.HOURS));
        userRepository.save(user);

        emailService.sendBookingWelcomeEmail(normalizedEmail, name != null ? name.trim() : null, rawToken);

        return user;
    }

    public boolean changePassword(String email, String newPassword) {
        if (!isPasswordValid(newPassword)) {
            return false;
        }
        var userOpt = userRepository.findByEmail(email);
        if (userOpt.isEmpty()) {
            return false;
        }
        User user = userOpt.get();
        user.setPassword(passwordEncoder.encode(newPassword));
        userRepository.save(user);
        return true;
    }

    /**
     * Builds or updates the ContactProfile attached to {@code user} from RegisterRequest fields,
     * setting status (e.g. "Activo" for active, "Pendiente Pago" for pending). Auto-fills
     * billing address from the location code when known. Caller is responsible for save().
     */
    private ContactProfile applyContactProfileFromRequest(
            User user, RegisterRequest request, String normalizedEmail, String status) {
        var existingProfile = contactProfileRepository
            .findFirstByEmailPrimaryIgnoreCaseOrEmailSecondaryIgnoreCaseOrEmailTertiaryIgnoreCaseOrRepresentativeEmailIgnoreCase(
                normalizedEmail, normalizedEmail, normalizedEmail, normalizedEmail);

        ContactProfile cp;
        if (existingProfile.isPresent()) {
            cp = existingProfile.get();
            // Only flip status if we're activating; never downgrade an active profile.
            if (!"Activo".equalsIgnoreCase(cp.getStatus()) || "Activo".equals(status)) {
                if (!status.equalsIgnoreCase(cp.getStatus())) {
                    cp.setStatus(status);
                    cp.setStatusChangedAt(LocalDateTime.now());
                }
            }
            user.setTenantId(cp.getId());
        } else {
            cp = new ContactProfile();
            cp.setId(System.currentTimeMillis());
            cp.setName(request.getName().trim());
            cp.setEmailPrimary(normalizedEmail);
            cp.setStatus(status);
            cp.setTenantType("Usuario Virtual");
            cp.setActive(true);
            cp.setCreatedAt(LocalDateTime.now());
            cp.setStatusChangedAt(LocalDateTime.now());
            cp.setChannel("Self-registration");
            user.setTenantId(cp.getId());
        }

        if (request.getCompany() != null && !request.getCompany().isBlank()) {
            cp.setBillingName(request.getCompany().trim());
        }
        if (request.getTaxId() != null && !request.getTaxId().isBlank()) {
            cp.setBillingTaxId(request.getTaxId().trim());
        }
        if (request.getTaxIdType() != null && !request.getTaxIdType().isBlank()) {
            cp.setBillingTaxIdType(request.getTaxIdType().trim());
        }
        if (request.getPhone() != null && !request.getPhone().isBlank()) {
            cp.setPhonePrimary(request.getPhone().trim());
        }

        if (request.getLocation() != null && !request.getLocation().isBlank()) {
            String loc = request.getLocation().toLowerCase().trim();
            if ("malaga".equals(loc)) {
                cp.setBillingAddress("Calle Alejandro Dumas, 17 · Oficinas");
                cp.setBillingCity("Málaga");
                cp.setBillingPostalCode("29004");
                cp.setBillingProvince("Málaga");
                cp.setBillingCountry("España");
            }
        }

        return cp;
    }

    private boolean isNonBlank(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private boolean isPasswordValid(String password) {
        // Enforce minimum length and basic complexity without over-constraining users.
        return password != null
                && password.length() >= 8
                && password.matches(".*[a-z].*")
                && password.matches(".*[A-Z].*")
                && password.matches(".*\\d.*")
                && password.matches(".*[!@#$%^&*()_+\\-=\\[\\]{};':\"\\\\|,.<>\\/?].*");
    }

    /**
     * Calls stripe-service /api/subscriptions/auto to create a subscription.
     * Customer already has a card from the SetupIntent, so Stripe charges immediately.
     * Returns Map with subscriptionId, customerId, firstInvoice.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> createAutoSubscription(String email, String name,
                                                        int baseAmountCents,
                                                        String description, String customerId,
                                                        boolean taxExempt) {
        try {
            String stripeServiceUrl = System.getenv("STRIPE_SERVICE_URL") != null
                ? System.getenv("STRIPE_SERVICE_URL")
                : "http://beworking-stripe-service:8081";

            Map<String, Object> body = new java.util.HashMap<>();
            body.put("customer_email", email);
            body.put("customer_name", name);
            body.put("amount_cents", baseAmountCents);
            body.put("currency", "eur");
            body.put("description", description);
            body.put("tenant", "bw");
            body.put("collection_method", "charge_automatically");
            body.put("tax_exempt", taxExempt);
            if (customerId != null && !customerId.isBlank()) {
                body.put("customer_id", customerId);
            }

            var client = new org.springframework.web.client.RestTemplate();
            var headers = new org.springframework.http.HttpHeaders();
            headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
            var entity = new org.springframework.http.HttpEntity<>(body, headers);

            Map<String, Object> response = client.postForObject(
                stripeServiceUrl + "/api/subscriptions/auto",
                entity,
                Map.class
            );

            if (response != null && response.containsKey("subscriptionId")) {
                logger.info("Created auto Stripe subscription {} for {} ({}c base)",
                    response.get("subscriptionId"), email, baseAmountCents);
                return response;
            }
        } catch (Exception e) {
            logger.error("Failed to create auto Stripe subscription for {}: {}", email, e.getMessage(), e);
        }
        return null;
    }

    private String hashToken(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(token.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hashed) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
