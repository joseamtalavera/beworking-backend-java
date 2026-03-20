package com.beworking.auth;

import com.beworking.contacts.ContactProfile;
import com.beworking.contacts.ContactProfileRepository;
import com.beworking.plans.PlanRepository;
import com.beworking.subscriptions.Subscription;
import com.beworking.subscriptions.SubscriptionRepository;
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

    public RegisterService(UserRepository userRepository, PasswordEncoder passwordEncoder,
                           EmailService emailService, ContactProfileRepository contactProfileRepository,
                           SubscriptionRepository subscriptionRepository, PlanRepository planRepository) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.emailService = emailService;
        this.contactProfileRepository = contactProfileRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.planRepository = planRepository;
    }

    /**
     * Creates a new user account with a trial subscription when inputs are valid and the email is unused.
     * Also creates a Subscription record and populates ContactProfile with billing data.
     */
    @Transactional
    public User registerUserWithTrial(RegisterRequest request) {
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
        user.setEmailConfirmed(true); // Auto-confirm: payment method already proves identity
        user.setStripeCustomerId(request.getStripeCustomerId());

        // Auto-link or create ContactProfile with billing data
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
            cp.setStatus("Trial");
            cp.setTenantType("Usuario Virtual");
            cp.setActive(true);
            cp.setCreatedAt(LocalDateTime.now());
            cp.setStatusChangedAt(LocalDateTime.now());
            cp.setChannel("Self-registration-trial");
            user.setTenantId(cp.getId());
        }

        // Update contact with company/billing data if provided
        if (request.getCompany() != null && !request.getCompany().isBlank()) {
            cp.setBillingName(request.getCompany().trim());
        }
        if (request.getTaxId() != null && !request.getTaxId().isBlank()) {
            cp.setBillingTaxId(request.getTaxId().trim());
        }
        if (request.getPhone() != null && !request.getPhone().isBlank()) {
            cp.setPhonePrimary(request.getPhone().trim());
        }

        // Auto-populate billing address from selected location
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
        contactProfileRepository.save(cp);
        userRepository.save(user);

        // Create subscription record if plan is provided
        if (request.getPlan() != null && !request.getPlan().isBlank()) {
            String planKey = request.getPlan().toLowerCase();
            // "basis" is legacy alias for "basic"
            if ("basis".equals(planKey)) planKey = "basic";

            var planOpt = planRepository.findByPlanKey(planKey);
            java.math.BigDecimal amount = planOpt.map(com.beworking.plans.Plan::getPrice)
                    .orElse(new java.math.BigDecimal("15.00"));
            String planLabel = planOpt.map(com.beworking.plans.Plan::getName)
                    .orElse(planKey.substring(0, 1).toUpperCase() + planKey.substring(1));

            Subscription sub = new Subscription();
            sub.setContactId(cp.getId());
            sub.setMonthlyAmount(amount);
            sub.setCurrency(planOpt.map(com.beworking.plans.Plan::getCurrency).orElse("EUR"));
            sub.setDescription("BeWorking " + planLabel);
            sub.setBillingMethod("stripe");
            sub.setStripeCustomerId(request.getStripeCustomerId());
            sub.setStartDate(LocalDate.now().plusDays(30)); // Billing starts after 30-day trial
            sub.setEndDate(null); // Ongoing subscription, no fixed end
            sub.setActive(true);
            sub.setCreatedAt(LocalDateTime.now());
            sub.setVatNumber(request.getTaxId());
            if (request.getTaxId() != null && !request.getTaxId().isBlank()) {
                sub.setVatPercent(0); // EU VAT reverse charge
            }

            // Create Stripe trial subscription
            if (request.getSetupIntentId() != null && !request.getSetupIntentId().isBlank()) {
                String stripeSubId = createStripeTrialSubscription(
                    request.getSetupIntentId(),
                    amount.multiply(new java.math.BigDecimal("100")).intValue(),
                    sub.getCurrency().toLowerCase(),
                    30,
                    "BeWorking " + planLabel,
                    planKey
                );
                if (stripeSubId != null) {
                    sub.setStripeSubscriptionId(stripeSubId);
                }
            }

            subscriptionRepository.save(sub);
        }

        // Send admin notification email
        emailService.sendRegistrationAdminNotification(
            name, normalizedEmail, request.getPhone(),
            request.getCompany(), request.getTaxId(),
            request.getPlan(), request.getLocation()
        );

        // Send welcome email to the user
        String planLabel = request.getPlan() != null ? request.getPlan() : "Basic";
        emailService.sendTrialWelcomeEmail(normalizedEmail, name.trim(), planLabel, request.getLocation());

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
     * Calls the Stripe service to create a trial subscription.
     * Returns the Stripe subscription ID, or null on failure.
     */
    private String createStripeTrialSubscription(String setupIntentId, int monthlyAmountCents,
                                                  String currency, int trialDays,
                                                  String description, String plan) {
        try {
            String stripeServiceUrl = System.getenv("STRIPE_SERVICE_URL") != null
                ? System.getenv("STRIPE_SERVICE_URL")
                : "http://beworking-stripe-service:8081";

            String jsonBody = String.format(
                "{\"setup_intent_id\":\"%s\",\"monthly_amount\":%d,\"currency\":\"%s\","
                + "\"trial_period_days\":%d,\"description\":\"%s\",\"plan\":\"%s\",\"tenant\":\"beworking\"}",
                setupIntentId.replace("\"", "\\\""),
                monthlyAmountCents,
                currency.replace("\"", "\\\""),
                trialDays,
                description.replace("\"", "\\\""),
                plan.replace("\"", "\\\"")
            );

            var client = new org.springframework.web.client.RestTemplate();
            var headers = new org.springframework.http.HttpHeaders();
            headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
            var entity = new org.springframework.http.HttpEntity<>(jsonBody, headers);

            @SuppressWarnings("unchecked")
            Map<String, Object> response = client.postForObject(
                stripeServiceUrl + "/api/subscriptions/trial",
                entity,
                Map.class
            );

            if (response != null && response.containsKey("subscriptionId")) {
                String subId = (String) response.get("subscriptionId");
                logger.info("Created Stripe trial subscription {} for setupIntent {}", subId, setupIntentId);
                return subId;
            }
        } catch (Exception e) {
            logger.error("Failed to create Stripe trial subscription for setupIntent {}: {}",
                setupIntentId, e.getMessage(), e);
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
