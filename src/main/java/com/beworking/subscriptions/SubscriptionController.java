package com.beworking.subscriptions;

import com.beworking.auth.User;
import com.beworking.auth.UserRepository;
import com.beworking.bookings.Producto;
import com.beworking.bookings.ProductoRepository;
import com.beworking.contacts.ContactProfile;
import com.beworking.contacts.ContactProfileRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClient;

@RestController
@RequestMapping("/api/subscriptions")
public class SubscriptionController {

    private static final Logger logger = LoggerFactory.getLogger(SubscriptionController.class);

    private final SubscriptionService subscriptionService;
    private final UserRepository userRepository;
    private final ContactProfileRepository contactRepository;
    private final ProductoRepository productoRepository;
    private final com.beworking.plans.PlanRepository planRepository;
    private final com.beworking.contacts.ViesVatService viesVatService;
    private final com.beworking.auth.EmailService emailService;
    private final com.beworking.auth.RegisterService registerService;
    private final RestClient http;
    private final com.beworking.bekey.BeKeyAccessService beKeyAccessService;
    private final com.beworking.bekey.BeKeyShareService beKeyShareService;


    @Value("${app.frontend-url:}")
    private String frontendUrl;

    public SubscriptionController(SubscriptionService subscriptionService,
                                  UserRepository userRepository,
                                  ContactProfileRepository contactRepository,
                                  ProductoRepository productoRepository,
                                  com.beworking.plans.PlanRepository planRepository,
                                  com.beworking.contacts.ViesVatService viesVatService,
                                  com.beworking.auth.EmailService emailService,
                                  com.beworking.auth.RegisterService registerService,
                                  com.beworking.bekey.BeKeyAccessService beKeyAccessService,
                                  com.beworking.bekey.BeKeyShareService beKeyShareService,
                                  @Value("${app.payments.base-url:http://beworking-stripe-service:8081}") String paymentsBaseUrl) {
        this.subscriptionService = subscriptionService;
        this.userRepository = userRepository;
        this.contactRepository = contactRepository;
        this.productoRepository = productoRepository;
        this.planRepository = planRepository;
        this.viesVatService = viesVatService;
        this.emailService = emailService;
        this.registerService = registerService;
        this.beKeyAccessService = beKeyAccessService;
        this.beKeyShareService = beKeyShareService;
        this.http = RestClient.builder().baseUrl(paymentsBaseUrl).build();
    }

    @GetMapping
    public ResponseEntity<?> list(
        Authentication authentication,
        @RequestParam(required = false) Long contactId
    ) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        boolean admin = isAdmin(authentication);
        // Non-admin users can only list subscriptions for their own contact
        if (!admin) {
            Optional<User> userOpt = userRepository.findByEmail(authentication.getName());
            if (userOpt.isEmpty()) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
            }
            User currentUser = userOpt.get();
            if (contactId == null || currentUser.getTenantId() == null || !currentUser.getTenantId().equals(contactId)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
            }
        }

        List<Subscription> subs = contactId != null
            ? subscriptionService.findByContactId(contactId)
            : subscriptionService.findAll();

        return ResponseEntity.ok(subs);
    }

    @PostMapping
    public ResponseEntity<?> create(
        Authentication authentication,
        @RequestBody CreateSubscriptionRequest request
    ) {
        if (!isAdmin(authentication)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        String billingMethod = request.getBillingMethod() != null ? request.getBillingMethod() : "stripe";

        // Resolve the desk product id from its name when the client didn't send a
        // productoId (e.g. admin Spaces floor-plan-click path, where the wizard only
        // carries the desk name). This mirrors BookingService.createPublicBooking,
        // which always resolves the producto by name — so admin-created desk subs
        // persist a productoId and occupy the floor plan, exactly like booking-app
        // subs. Without it the sub saves productoId=null and the desk stays "available".
        if (request.getProductoId() == null
                && request.getProductName() != null && !request.getProductName().isBlank()) {
            productoRepository.findByNombreIgnoreCase(request.getProductName().trim())
                .ifPresent(p -> request.setProductoId(p.getId()));
        }

        // Seasonal desk zones (e.g. summer MA1O5) auto-terminate at their window
        // end: the DB sub gets an end_date and the Stripe sub a cancel_at, so it
        // doesn't renew past the season.
        final LocalDate seasonEnd = request.getProductoId() == null ? null
            : productoRepository.findById(request.getProductoId())
                .map(com.beworking.bookings.Producto::getNombre)
                .map(com.beworking.bookings.CoworkZone::seasonEndForProduct)
                .orElse(null);

        // Bank-transfer subs are billed entirely in our own DB — no Stripe
        // subscription and no Stripe-hosted invoice. We skip the whole Stripe
        // block below, persist the sub as billing_method='bank_transfer', and
        // issue a local Pendiente invoice immediately; the monthly
        // LocalSubscriptionScheduler covers subsequent periods. (Previously this
        // label silently went through Stripe, leaving phantom 'open' invoices on
        // bank-transfer customers — the bug this fixes.)
        boolean isBankTransfer = "bank_transfer".equals(billingMethod);

        // --- Stripe flow (existing logic) ---
        String stripeSubId = request.getStripeSubscriptionId();
        Map<String, Object> stripeResponse = null;
        Map<String, Object> stripeDetails = null; // populated when linking a pre-existing Stripe sub

        // If a pre-existing Stripe subscription ID was provided, fetch its details
        if (stripeSubId != null && !stripeSubId.isBlank()) {
            try {
                String tenant = "GT".equalsIgnoreCase(request.getCuenta()) ? "gt" : null;
                String detailsUri = "/api/subscriptions/" + stripeSubId + "/details";
                if (tenant != null) detailsUri += "?tenant=" + tenant;
                @SuppressWarnings("unchecked")
                Map<String, Object> resp = http.get()
                    .uri(detailsUri)
                    .retrieve()
                    .body(Map.class);
                stripeDetails = resp;
                // Auto-fill customer ID from Stripe
                String customerId = (String) stripeDetails.get("customerId");
                if (customerId != null) request.setStripeCustomerId(customerId);
                logger.info("Fetched Stripe details for pre-existing subscription {}", stripeSubId);
            } catch (Exception e) {
                logger.warn("Could not fetch Stripe subscription details for {}: {}", stripeSubId, e.getMessage());
            }
        }

        // If no Stripe subscription ID provided, create one via stripe-service.
        // Bank-transfer subs never touch Stripe, so skip this entirely.
        if (!isBankTransfer && (stripeSubId == null || stripeSubId.isBlank())) {
            Optional<ContactProfile> contactOpt = contactRepository.findById(request.getContactId());
            if (contactOpt.isEmpty()) {
                Map<String, Object> error = new HashMap<>();
                error.put("error", "Contact not found");
                return ResponseEntity.badRequest().body(error);
            }

            ContactProfile contact = contactOpt.get();
            String email = contact.getEmailPrimary();
            if (email == null || email.isBlank()) {
                email = contact.getEmailSecondary();
            }
            if (email == null || email.isBlank()) {
                Map<String, Object> error = new HashMap<>();
                error.put("error", "Contact has no email address for Stripe");
                return ResponseEntity.badRequest().body(error);
            }

            try {
                // Resolve VAT: use request vatNumber, fallback to contact billing tax ID
                String resolvedVat = request.getVatNumber();
                if ((resolvedVat == null || resolvedVat.isBlank()) && contact.getBillingTaxId() != null) {
                    resolvedVat = contact.getBillingTaxId();
                }

                // Determine tax exemption using full VIES check (prefix + country hint)
                String cuenta = request.getCuenta() != null ? request.getCuenta().toUpperCase() : "PT";
                String supplierCountry = "GT".equals(cuenta) ? "EE" : "ES";
                boolean taxExempt = false;

                if (resolvedVat != null && !resolvedVat.isBlank()) {
                    // First check prefix
                    if (isEuVatNumber(resolvedVat)) {
                        String prefix = resolvedVat.trim().replaceAll("\\s+", "").toUpperCase().substring(0, 2);
                        taxExempt = !prefix.equals(supplierCountry);
                    } else {
                        // No prefix — try VIES with country hint from billing_country
                        String countryHint = SubscriptionService.countryNameToIso(contact.getBillingCountry());
                        if (countryHint != null) {
                            var viesResult = viesVatService.validate(resolvedVat, countryHint);
                            if (viesResult.valid()) {
                                taxExempt = !countryHint.equals(supplierCountry);
                                logger.info("VIES confirmed {} as {} — taxExempt={}", resolvedVat, countryHint, taxExempt);
                            } else {
                                logger.info("VIES could not validate {} (hint={}) — applying default VAT", resolvedVat, countryHint);
                            }
                        }
                    }
                }

                // Send base amount (pre-tax) to Stripe. When apply_tax=true (i.e.
                // not tax-exempt), stripe-service attaches default_tax_rates with the
                // configured IVA percentage, and Stripe layers VAT on top automatically.
                // Pre-fix: we were sending base + VAT to Stripe AND letting Stripe add
                // VAT again — double-taxation. Customer was charged ~21% over.
                // Stored amount is the MONTHLY rate; a non-monthly interval bills
                // it × months in the cycle (quarter ×3, half-year ×6, year ×12).
                int intervalMonths = SubscriptionService.monthsForInterval(request.getBillingInterval());
                BigDecimal baseAmount = request.getMonthlyAmount().multiply(BigDecimal.valueOf(intervalMonths));
                int vatPercent = taxExempt ? 0 : (request.getVatPercent() != null ? request.getVatPercent() : 21);
                int amountCents = baseAmount.multiply(BigDecimal.valueOf(100)).intValue();

                logger.info("Creating Stripe sub: base={} vat={}% taxExempt={} (Stripe applies VAT via default_tax_rates)",
                    baseAmount, vatPercent, taxExempt);

                Map<String, Object> stripeRequest = new HashMap<>();
                stripeRequest.put("customer_email", email);
                stripeRequest.put("customer_name", contact.getName());
                stripeRequest.put("amount_cents", amountCents);
                stripeRequest.put("currency", request.getCurrency() != null ? request.getCurrency().toLowerCase() : "eur");
                stripeRequest.put("description", request.getDescription() != null ? request.getDescription() : "Oficina Virtual");
                stripeRequest.put("tenant", "GT".equalsIgnoreCase(request.getCuenta()) ? "gt" : "bw");

                // Reuse existing Stripe customer for this contact — any prior sub
                // (active OR cancelled) in the SAME cuenta keeps the same Stripe
                // customer. We must filter by cuenta because PT and GT live in
                // separate Stripe accounts; passing a PT customer ID to the GT
                // tenant fails with "No such customer".
                String reqCuenta = request.getCuenta() != null ? request.getCuenta().toUpperCase() : "PT";
                subscriptionService.findByContactId(request.getContactId()).stream()
                    .filter(s -> s.getStripeCustomerId() != null && !s.getStripeCustomerId().isBlank())
                    .filter(s -> reqCuenta.equalsIgnoreCase(s.getCuenta()))
                    .findFirst()
                    .ifPresent(s -> stripeRequest.put("customer_id", s.getStripeCustomerId()));

                stripeRequest.put("vat_number", taxExempt ? resolvedVat : "");
                stripeRequest.put("tax_exempt", taxExempt);

                // Bill full month from startDate. Never prorate.
                //   - startDate = today  → Stripe invoices now for full month; next cycle today+1mo
                //   - startDate = future → trial_end until startDate; first invoice on startDate
                LocalDate startDate = request.getStartDate() != null ? request.getStartDate() : LocalDate.now();
                if (startDate.isAfter(LocalDate.now())) {
                    long trialEndEpoch = startDate.atStartOfDay().toEpochSecond(ZoneOffset.UTC);
                    stripeRequest.put("trial_end", trialEndEpoch);
                }
                stripeRequest.put("proration_behavior", "none");
                if (seasonEnd != null) {
                    // Stop renewing at season end (start of the day after).
                    stripeRequest.put("cancel_at",
                        seasonEnd.plusDays(1).atStartOfDay(java.time.ZoneId.of("Europe/Madrid")).toEpochSecond());
                }

                // Pass billing interval (month, quarter, year)
                String interval = request.getBillingInterval() != null ? request.getBillingInterval() : "month";
                stripeRequest.put("interval", interval);

                // Admin-created subs: customer hasn't onboarded a payment method
                // yet, so use send_invoice + card+SEPA. Stripe emails the hosted
                // invoice; customer adds payment method there. After first paid
                // invoice, stripe-service auto-switches the sub to
                // charge_automatically so subsequent cycles bill silently.
                stripeRequest.put("collection_method", "send_invoice");
                stripeRequest.put("payment_method_types", List.of("card", "sepa_debit"));

                // Pass billing details from contact profile to Stripe customer
                Map<String, Object> billing = new HashMap<>();
                if (contact.getBillingName() != null && !contact.getBillingName().isBlank()) {
                    billing.put("company", contact.getBillingName());
                }
                if (contact.getBillingAddress() != null && !contact.getBillingAddress().isBlank()) {
                    billing.put("line1", contact.getBillingAddress());
                }
                if (contact.getBillingCity() != null && !contact.getBillingCity().isBlank()) {
                    billing.put("city", contact.getBillingCity());
                }
                if (contact.getBillingProvince() != null && !contact.getBillingProvince().isBlank()) {
                    billing.put("state", contact.getBillingProvince());
                }
                if (contact.getBillingPostalCode() != null && !contact.getBillingPostalCode().isBlank()) {
                    billing.put("postal_code", contact.getBillingPostalCode());
                }
                if (contact.getBillingCountry() != null && !contact.getBillingCountry().isBlank()) {
                    billing.put("country", mapCountryToIso(contact.getBillingCountry()));
                }
                if (contact.getBillingTaxId() != null && !contact.getBillingTaxId().isBlank()) {
                    billing.put("tax_id", contact.getBillingTaxId());
                }
                if (!billing.isEmpty()) {
                    stripeRequest.put("billing", billing);
                }

                @SuppressWarnings("unchecked")
                Map<String, Object> resp = http.post()
                    .uri("/api/subscriptions/auto")
                    .header("Content-Type", "application/json")
                    .body(stripeRequest)
                    .retrieve()
                    .body(Map.class);
                stripeResponse = resp;

                stripeSubId = (String) stripeResponse.get("subscriptionId");
                String customerId = (String) stripeResponse.get("customerId");
                request.setStripeSubscriptionId(stripeSubId);
                request.setStripeCustomerId(customerId);

                logger.info("Created Stripe subscription {} for contact {}", stripeSubId, request.getContactId());
            } catch (Exception e) {
                logger.error("Failed to create Stripe subscription: {}", e.getMessage(), e);
                Map<String, Object> error = new HashMap<>();
                error.put("error", "Failed to create Stripe subscription: " + e.getMessage());
                return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(error);
            }
        }

        // Check for duplicate stripe subscription ID (bank-transfer subs have none)
        if (!isBankTransfer && stripeSubId != null && !stripeSubId.isBlank()
                && subscriptionService.findByStripeSubscriptionId(stripeSubId).isPresent()) {
            Map<String, Object> error = new HashMap<>();
            error.put("error", "Subscription with this Stripe ID already exists");
            return ResponseEntity.status(HttpStatus.CONFLICT).body(error);
        }

        // Resolve VAT number: use request vatNumber, fallback to contact billing tax ID
        String effectiveVat = request.getVatNumber();
        if (effectiveVat == null || effectiveVat.isBlank()) {
            contactRepository.findById(request.getContactId()).ifPresent(cp -> {
                if (cp.getBillingTaxId() != null && !cp.getBillingTaxId().isBlank()) {
                    request.setVatNumber(cp.getBillingTaxId());
                }
            });
            effectiveVat = request.getVatNumber();
        }

        Subscription sub = new Subscription();
        sub.setContactId(request.getContactId());
        sub.setBillingMethod(billingMethod);
        sub.setStripeSubscriptionId(request.getStripeSubscriptionId());
        sub.setStripeCustomerId(request.getStripeCustomerId());
        sub.setMonthlyAmount(request.getMonthlyAmount());
        sub.setCurrency(request.getCurrency() != null ? request.getCurrency() : "EUR");
        sub.setCuenta(request.getCuenta() != null ? request.getCuenta() : "PT");
        sub.setDescription(request.getDescription() != null ? request.getDescription() : "Oficina Virtual");
        sub.setBillingInterval(request.getBillingInterval() != null ? request.getBillingInterval() : "month");
        boolean hasVatNumber = effectiveVat != null && !effectiveVat.isBlank();
        sub.setVatNumber(hasVatNumber ? effectiveVat : null);
        // Intra-community reverse charge (0%) applies ONLY when the customer's EU
        // VAT prefix is a DIFFERENT country than the supplier (ES for PT cuenta,
        // EE for GT). A domestic VAT number (e.g. ES customer on the PT/ES entity)
        // is NOT intra-community and must carry the local rate. Mirrors the
        // supplierCountry logic in the Stripe block above; the naive
        // isEuVatNumber() check alone wrongly zeroed VAT for domestic ES subs.
        String subCuenta = request.getCuenta() != null ? request.getCuenta().toUpperCase() : "PT";
        String subSupplierCountry = "GT".equals(subCuenta) ? "EE" : "ES";
        boolean euIntracommunity = isEuVatNumber(effectiveVat)
                && !effectiveVat.trim().replaceAll("\\s+", "").substring(0, 2).toUpperCase().equals(subSupplierCountry);
        sub.setVatPercent(euIntracommunity ? 0 : (request.getVatPercent() != null ? request.getVatPercent() : 21));
        sub.setStartDate(request.getStartDate() != null ? request.getStartDate() : LocalDate.now());
        sub.setEndDate(seasonEnd != null ? seasonEnd : request.getEndDate());
        sub.setProductoId(request.getProductoId());
        sub.setActive(true);
        sub.setCreatedAt(LocalDateTime.now());
        sub.setUpdatedAt(LocalDateTime.now());

        Subscription saved = subscriptionService.save(sub);

        // Bank-transfer: issue the first Pendiente invoice now so the customer
        // has something to pay immediately (the monthly LocalSubscriptionScheduler
        // covers following periods). Best-effort — never fail the create. Note:
        // mutates `saved` in place (no reassignment) to keep it effectively final
        // for the email lambda below.
        if (isBankTransfer) {
            try {
                String month = java.time.YearMonth.now().toString();
                subscriptionService.createBankTransferInvoice(saved, month);
                saved.setLastInvoicedMonth(month);
                subscriptionService.save(saved);
                logger.info("Created first bank_transfer Pendiente invoice for sub {} (month={})",
                        saved.getId(), month);
            } catch (Exception e) {
                logger.error("Failed to create first bank_transfer invoice for sub {}: {}",
                        saved.getId(), e.getMessage(), e);
            }
        }

        // Best-effort: grant BeKey door access for this subscription's category
        // (coworking → MA1O1; virtual office → no standing access). Never fail the
        // request if Akiles/BeKey is unreachable.
        try {
            String bekeyCategory = subscriptionService.resolveSubscriptionCategory(saved);
            beKeyAccessService.grantForSubscription(
                    saved.getContactId(), saved.getId().longValue(), bekeyCategory);
        } catch (Exception ex) {
            logger.warn("BeKey grant on sub-create failed (sub {}): {}", saved.getId(), ex.getMessage());
        }

        // Notify admin (info@be-working.com) and welcome the customer.
        // Best-effort: any failure is logged but doesn't fail the request — the
        // subscription is already created in Stripe and persisted locally.
        try {
            contactRepository.findById(saved.getContactId()).ifPresent(contact -> {
                String contactEmail = contact.getEmailPrimary() != null && !contact.getEmailPrimary().isBlank()
                        ? contact.getEmailPrimary()
                        : contact.getEmailSecondary();
                String contactName = contact.getName();
                String amountStr = saved.getMonthlyAmount() != null ? saved.getMonthlyAmount().toPlainString() : "—";

                emailService.sendSubscriptionAdminNotification(
                        contactName, contactEmail, saved.getDescription(),
                        amountStr, saved.getCurrency(), saved.getBillingInterval(),
                        saved.getCuenta(), saved.getStripeSubscriptionId());

                if (contactEmail != null && !contactEmail.isBlank()) {
                    // For admin-created subs the customer doesn't know their password
                    // (we generated a random one) and the first invoice arrives only
                    // when Stripe issues it. Surface both in the welcome email.
                    String firstInvoiceIso = saved.getStartDate() != null
                            ? saved.getStartDate().toString() : null;
                    String passwordSetupLink = null;
                    try {
                        // Ensure the customer has a login account. Admin-created
                        // subscriptions attach to a pre-existing (often legacy /
                        // self-registered) contact that may have no users row —
                        // without this they can never log in or recover a
                        // password (#205). Idempotent: an existing user is
                        // returned untouched (only a missing tenantId backfilled).
                        com.beworking.auth.RegisterService.BookingUserProvisionResult prov =
                                registerService.provisionBookingUser(
                                        contactEmail, contactName, saved.getContactId());
                        if (prov.user() != null && frontendUrl != null && !frontendUrl.isBlank()) {
                            String token = registerService.generatePasswordSetupToken(
                                    prov.user().getId(), java.time.Duration.ofDays(7));
                            if (token != null) {
                                passwordSetupLink = frontendUrl + "/reset-password?token=" + token;
                            }
                        }
                    } catch (Exception tokenEx) {
                        logger.warn("Failed to provision user / generate password setup token for sub {}: {}",
                                saved.getId(), tokenEx.getMessage());
                    }

                    emailService.sendSubscriptionWelcomeEmail(
                            contactEmail, contactName,
                            saved.getDescription(), saved.getCuenta(),
                            firstInvoiceIso, passwordSetupLink);
                }
            });
        } catch (Exception e) {
            logger.warn("Failed to send subscription notification emails for sub {}: {}",
                    saved.getId(), e.getMessage(), e);
        }

        // Create local invoices from Stripe data
        if (stripeResponse != null && stripeResponse.containsKey("firstInvoice")
                && !"trialing".equals(stripeResponse.get("status"))) {
            // Auto-created subscription: create Pendiente invoice from first invoice
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> firstInvoice = (Map<String, Object>) stripeResponse.get("firstInvoice");
                if (firstInvoice != null) {
                    SubscriptionInvoicePayload invoicePayload = new SubscriptionInvoicePayload();
                    invoicePayload.setStripeSubscriptionId(saved.getStripeSubscriptionId());
                    invoicePayload.setStripeInvoiceId((String) firstInvoice.get("stripeInvoiceId"));
                    invoicePayload.setStripePaymentIntentId((String) firstInvoice.get("paymentIntentId"));
                    invoicePayload.setSubtotalCents(toInteger(firstInvoice.get("subtotalCents")));
                    invoicePayload.setTaxCents(toInteger(firstInvoice.get("taxCents")));
                    invoicePayload.setInvoicePdf((String) firstInvoice.get("invoicePdf"));
                    invoicePayload.setPeriodStart((String) firstInvoice.get("periodStart"));
                    invoicePayload.setPeriodEnd((String) firstInvoice.get("periodEnd"));
                    invoicePayload.setStatus("pending");
                    subscriptionService.createInvoiceFromSubscription(saved, invoicePayload);
                    logger.info("Created Pendiente invoice for subscription {}", saved.getStripeSubscriptionId());
                }
            } catch (Exception e) {
                logger.warn("Failed to create local Pendiente invoice: {}", e.getMessage(), e);
            }
        } else if (stripeDetails != null && stripeDetails.containsKey("invoices")) {
            // Pre-existing Stripe subscription: sync all invoices
            // Do NOT pass stripeInvoiceNumber — Stripe's auto-generated numbers should not
            // be used; let the system generate proper PT/GT/OF-prefixed numbers.
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> invoices = (List<Map<String, Object>>) stripeDetails.get("invoices");
            if (invoices != null) {
                for (Map<String, Object> inv : invoices) {
                    try {
                        SubscriptionInvoicePayload payload = new SubscriptionInvoicePayload();
                        payload.setStripeSubscriptionId(saved.getStripeSubscriptionId());
                        payload.setStripeCustomerId(saved.getStripeCustomerId());
                        payload.setStripeInvoiceId((String) inv.get("stripeInvoiceId"));
                        payload.setStripePaymentIntentId((String) inv.get("stripePaymentIntentId"));
                        payload.setSubtotalCents(toInteger(inv.get("subtotalCents")));
                        payload.setTaxCents(toInteger(inv.get("taxCents")));
                        payload.setInvoicePdf((String) inv.get("invoicePdf"));
                        payload.setPeriodStart((String) inv.get("periodStart"));
                        payload.setPeriodEnd((String) inv.get("periodEnd"));
                        payload.setStatus((String) inv.get("status"));
                        subscriptionService.createInvoiceFromSubscription(saved, payload);
                        logger.info("Synced invoice for Stripe invoice {}", inv.get("stripeInvoiceId"));
                    } catch (Exception e) {
                        logger.warn("Failed to sync invoice {}: {}", inv.get("stripeInvoiceId"), e.getMessage());
                    }
                }
            }
        }

        return ResponseEntity.status(HttpStatus.CREATED).body(saved);
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(
        Authentication authentication,
        @PathVariable Integer id,
        @RequestBody UpdateSubscriptionRequest request
    ) {
        if (!isAdmin(authentication)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        Optional<Subscription> subOpt = subscriptionService.findById(id);
        if (subOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        Subscription sub = subOpt.get();
        boolean wasActive = Boolean.TRUE.equals(sub.getActive());
        if (request.getStripeSubscriptionId() != null) sub.setStripeSubscriptionId(request.getStripeSubscriptionId());
        if (request.getStripeCustomerId() != null) sub.setStripeCustomerId(request.getStripeCustomerId());
        if (request.getCuenta() != null) sub.setCuenta(request.getCuenta());
        if (request.getDescription() != null) sub.setDescription(request.getDescription());
        if (request.getMonthlyAmount() != null) sub.setMonthlyAmount(request.getMonthlyAmount());
        if (request.getVatPercent() != null) sub.setVatPercent(request.getVatPercent());
        if (request.getEndDate() != null) sub.setEndDate(request.getEndDate());
        if (request.getProductoId() != null) sub.setProductoId(request.getProductoId());
        // Only fire cancellation side-effects on a genuine active -> inactive transition,
        // so re-saving an already-inactive sub doesn't re-send emails or re-revoke access.
        boolean deactivating = false;
        if (request.getActive() != null) {
            sub.setActive(request.getActive());
            if (!request.getActive() && sub.getEndDate() == null) {
                sub.setEndDate(LocalDate.now());
            }
            // Cancel in Stripe when deactivating
            if (!request.getActive() && sub.getStripeSubscriptionId() != null && !sub.getStripeSubscriptionId().isBlank()) {
                cancelStripeSubscription(sub);
            }
            deactivating = wasActive && !request.getActive();
        }
        sub.setUpdatedAt(LocalDateTime.now());

        Subscription saved = subscriptionService.save(sub);

        // Admin-app cancel goes through this PUT (active=false); run the same
        // BeKey-revoke + email-info@/customer + tenant_type side-effects the
        // user-app DELETE path runs, so admin cancellations aren't silent.
        if (deactivating) {
            runCancellationSideEffects(saved, id, authentication);
        }
        return ResponseEntity.ok(saved);
    }

    @PostMapping("/{id}/link-stripe")
    public ResponseEntity<?> linkStripe(
        Authentication authentication,
        @PathVariable Integer id,
        @RequestBody Map<String, String> body
    ) {
        if (!isAdmin(authentication)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        String stripeSubId = body.get("stripeSubscriptionId");
        if (stripeSubId == null || stripeSubId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "stripeSubscriptionId is required"));
        }

        Optional<Subscription> subOpt = subscriptionService.findById(id);
        if (subOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        Subscription sub = subOpt.get();
        String tenant = "GT".equalsIgnoreCase(sub.getCuenta()) ? "gt" : null;

        // For subscription schedules (sub_sched_...), just save the ID.
        // When the schedule activates, Stripe fires customer.subscription.created
        // which triggers /api/webhooks/subscription-activated to swap the schedule
        // ID for the real subscription ID and invoices flow automatically.
        if (stripeSubId.startsWith("sub_sched_")) {
            sub.setStripeSubscriptionId(stripeSubId);
            sub.setUpdatedAt(LocalDateTime.now());
            subscriptionService.save(sub);
            logger.info("Linked Stripe subscription schedule {} to local subscription {}", stripeSubId, sub.getId());

            Map<String, Object> response = new HashMap<>();
            response.put("subscription", sub);
            response.put("stripeSubscriptionId", stripeSubId);
            response.put("invoicesCreated", 0);
            response.put("invoicesTotal", 0);
            response.put("message", "Subscription schedule linked. Invoices will be created automatically when the schedule activates.");
            return ResponseEntity.ok(response);
        }

        // Fetch subscription details and invoices from stripe-service
        Map<String, Object> stripeDetails;
        try {
            String uri = "/api/subscriptions/" + stripeSubId + "/details";
            if (tenant != null) uri += "?tenant=" + tenant;
            @SuppressWarnings("unchecked")
            Map<String, Object> resp = http.get()
                .uri(uri)
                .retrieve()
                .body(Map.class);
            stripeDetails = resp;
        } catch (Exception e) {
            logger.error("Failed to fetch Stripe subscription details: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("error", "Failed to fetch from Stripe: " + e.getMessage()));
        }

        // Link Stripe IDs
        String customerId = (String) stripeDetails.get("customerId");
        sub.setStripeSubscriptionId(stripeSubId);
        if (customerId != null) sub.setStripeCustomerId(customerId);
        sub.setUpdatedAt(LocalDateTime.now());
        subscriptionService.save(sub);
        logger.info("Linked Stripe subscription {} (customer={}) to local subscription {}", stripeSubId, customerId, sub.getId());

        // Create missing invoices
        // Do NOT pass stripeInvoiceNumber — Stripe's auto-generated numbers should not
        // be used; let the system generate proper PT/GT/OF-prefixed numbers.
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> invoices = (List<Map<String, Object>>) stripeDetails.get("invoices");
        int created = 0;
        if (invoices != null) {
            for (Map<String, Object> inv : invoices) {
                try {
                    SubscriptionInvoicePayload payload = new SubscriptionInvoicePayload();
                    payload.setStripeSubscriptionId(stripeSubId);
                    payload.setStripeCustomerId(customerId);
                    payload.setStripeInvoiceId((String) inv.get("stripeInvoiceId"));
                    payload.setStripePaymentIntentId((String) inv.get("stripePaymentIntentId"));
                    payload.setSubtotalCents(toInteger(inv.get("subtotalCents")));
                    payload.setTaxCents(toInteger(inv.get("taxCents")));
                    payload.setInvoicePdf((String) inv.get("invoicePdf"));
                    payload.setPeriodStart((String) inv.get("periodStart"));
                    payload.setPeriodEnd((String) inv.get("periodEnd"));
                    payload.setStatus((String) inv.get("status"));

                    Map<String, Object> result = subscriptionService.createInvoiceFromSubscription(sub, payload);
                    if (result != null) {
                        created++;
                        logger.info("Created invoice {} for Stripe invoice {}", result.get("invoiceNumber"), inv.get("stripeInvoiceId"));
                    }
                } catch (Exception e) {
                    logger.warn("Failed to create invoice for Stripe invoice {}: {}", inv.get("stripeInvoiceId"), e.getMessage());
                }
            }
        }

        Map<String, Object> response = new HashMap<>();
        response.put("subscription", sub);
        response.put("stripeSubscriptionId", stripeSubId);
        response.put("stripeCustomerId", customerId);
        response.put("invoicesCreated", created);
        response.put("invoicesTotal", invoices != null ? invoices.size() : 0);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/desk-occupancy/summary")
    public ResponseEntity<?> deskOccupancySummary(Authentication authentication) {
        if (!isAdmin(authentication)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        List<Subscription> activeSubs = subscriptionService.findActiveDeskSubscriptions();

        // Resolve each active desk sub's product name once, to bucket by zone.
        Set<Long> productIds = activeSubs.stream()
            .map(Subscription::getProductoId)
            .filter(java.util.Objects::nonNull)
            .collect(java.util.stream.Collectors.toSet());
        Map<Long, String> productNames = new HashMap<>();
        productoRepository.findAllById(productIds).forEach(p ->
            productNames.put(p.getId(), p.getNombre() == null ? "" : p.getNombre().toUpperCase()));

        // One row per coworking zone that is bookable today (so the summer A5 zone
        // appears only during its window). Top-level totals stay the primary zone
        // for backward compatibility with the existing single-row card.
        List<Map<String, Object>> zones = new ArrayList<>();
        long primaryTotal = 0, primaryOccupied = 0;
        for (com.beworking.bookings.CoworkZone zone : com.beworking.bookings.CoworkZone.ALL) {
            // All zones are permanent fixtures now (a zone may be blocked for
            // booking outside its window, but it still shows in occupancy).
            String prefixUpper = zone.prefix.toUpperCase();
            long total = productoRepository.countByNombrePrefix(zone.prefix);
            long occupied = activeSubs.stream()
                .map(Subscription::getProductoId)
                .filter(java.util.Objects::nonNull)
                .map(productNames::get)
                .filter(n -> n != null && n.startsWith(prefixUpper))
                .count();
            Map<String, Object> z = new HashMap<>();
            z.put("roomCode", zone.roomCode);
            z.put("prefix", zone.prefix);
            z.put("akilesGroup", zone.akilesGroup);
            z.put("total", total);
            z.put("occupied", occupied);
            z.put("activeFrom", zone.activeFrom == null ? null : zone.activeFrom.toString());
            z.put("activeTo", zone.activeTo == null ? null : zone.activeTo.toString());
            zones.add(z);
            if (zones.size() == 1) { primaryTotal = total; primaryOccupied = occupied; }
        }

        Map<String, Object> body = new HashMap<>();
        body.put("totalDesks", primaryTotal);
        body.put("occupiedDesks", primaryOccupied);
        body.put("zones", zones);
        return ResponseEntity.ok(body);
    }

    @GetMapping("/desk-occupancy")
    public ResponseEntity<?> deskOccupancy(Authentication authentication) {
        if (!isAdmin(authentication)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        List<Subscription> activeSubs = subscriptionService.findActiveDeskSubscriptions();
        List<Map<String, Object>> result = new ArrayList<>();

        for (Subscription sub : activeSubs) {
            Map<String, Object> entry = new HashMap<>();
            entry.put("subscriptionId", sub.getId());
            entry.put("contactId", sub.getContactId());
            entry.put("productoId", sub.getProductoId());
            entry.put("monthlyAmount", sub.getMonthlyAmount());
            entry.put("startDate", sub.getStartDate());
            entry.put("description", sub.getDescription());

            // Resolve contact name
            contactRepository.findById(sub.getContactId()).ifPresent(cp ->
                entry.put("contactName", cp.getName())
            );

            // Resolve product name and extract desk number
            productoRepository.findById(sub.getProductoId()).ifPresent(p -> {
                entry.put("productName", p.getNombre());
            });

            result.add(entry);
        }

        return ResponseEntity.ok(result);
    }

    /**
     * Lists the desk-slot products (MA1O1-N) so the admin Add-Subscription dialog
     * can offer a desk picker. These products live only in the productos table —
     * the booking/public lookup endpoints are rooms-first and never surface them.
     * Returns the broad MA1O1- prefix; the frontend narrows to MA1O1-<digits>.
     */
    @GetMapping("/desk-products")
    public ResponseEntity<?> deskProducts(Authentication authentication) {
        if (!isAdmin(authentication)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Producto p : productoRepository.findByNombrePrefix("MA1O1-")) {
            Map<String, Object> entry = new HashMap<>();
            entry.put("id", p.getId());
            entry.put("nombre", p.getNombre());
            entry.put("tipo", p.getTipo());
            result.add(entry);
        }
        return ResponseEntity.ok(result);
    }

    /**
     * Desk-slot products (MA1O1-N) each flagged with whether they are free
     * (not linked to an active desk subscription). Accessible to any
     * authenticated user so the self-service upgrade dialog can offer only
     * available desks; admins use the same data in the add/edit-sub dialogs.
     */
    @GetMapping("/desk-products/available")
    public ResponseEntity<?> availableDeskProducts(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        java.util.Set<Long> occupied = subscriptionService.findActiveDeskSubscriptions().stream()
            .map(Subscription::getProductoId)
            .filter(java.util.Objects::nonNull)
            .collect(java.util.stream.Collectors.toSet());
        // Every coworking zone bookable today (permanent MA1O1 + seasonal MA1O5/A5
        // during its window), so both zones' desks are assignable.
        java.time.LocalDate today = java.time.LocalDate.now();
        List<Map<String, Object>> result = new ArrayList<>();
        for (com.beworking.bookings.CoworkZone zone : com.beworking.bookings.CoworkZone.ALL) {
            if (!zone.isActiveOn(today)) continue;
            for (Producto p : productoRepository.findByNombrePrefix(zone.prefix)) {
                Map<String, Object> entry = new HashMap<>();
                entry.put("id", p.getId());
                entry.put("nombre", p.getNombre());
                entry.put("available", !occupied.contains(p.getId()));
                entry.put("zone", zone.roomCode);
                result.add(entry);
            }
        }
        return ResponseEntity.ok(result);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> deactivate(Authentication authentication, @PathVariable Integer id) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        Optional<Subscription> subOpt = subscriptionService.findById(id);
        if (subOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        Subscription sub = subOpt.get();

        // Admins can cancel any sub; non-admins only their own.
        if (!isAdmin(authentication)) {
            String email = authentication.getName();
            var contactOpt = contactRepository.findFirstByEmailPrimaryIgnoreCaseOrEmailSecondaryIgnoreCaseOrEmailTertiaryIgnoreCaseOrRepresentativeEmailIgnoreCase(
                email, email, email, email);
            if (contactOpt.isEmpty() || !contactOpt.get().getId().equals(sub.getContactId())) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
            }
        }

        LocalDate paidThrough = null;
        if (sub.getStripeSubscriptionId() != null && !sub.getStripeSubscriptionId().isBlank()) {
            paidThrough = cancelStripeSubscription(sub);
        }
        subscriptionService.deactivate(id, paidThrough);

        runCancellationSideEffects(sub, id, authentication);

        return ResponseEntity.noContent().build();
    }

    /**
     * Side-effects that must run whenever a subscription is cancelled, regardless of
     * the entry point — the user-app DELETE /{id} or the admin-app PUT /{id} with
     * active=false. Revokes BeKey door access, cascade-revokes shares the member
     * handed out, emails info@ + the customer, and drops the free-booking tenant_type
     * if this was the contact's last active sub. All steps best-effort; never blocks
     * the request. The DELETE and PUT paths previously diverged here — only DELETE
     * sent the cancellation email — so admin cancellations went out silently.
     */
    private void runCancellationSideEffects(Subscription sub, Integer id, Authentication authentication) {
        // Best-effort: revoke any BeKey door access tied to this subscription (#149).
        try {
            beKeyAccessService.revokeForSubscription(id.longValue(), "subscription cancelled");
        } catch (Exception ex) {
            logger.warn("BeKey revoke on sub-cancel failed (sub {}): {}", id, ex.getMessage());
        }

        // Cascade (#243): if this cancellation leaves the member with no real
        // access left, revoke the shares they handed out — you can't lend a key
        // you no longer hold. A still-active sub/booking keeps their shares.
        try {
            Long sharerContactId = sub.getContactId();
            if (sharerContactId != null) {
                boolean stillHasAccess = beKeyAccessService.listForContact(sharerContactId).stream()
                        .anyMatch(g -> g.getSource() != com.beworking.bekey.BeKeyAccess.Source.shared);
                if (!stillHasAccess) {
                    beKeyShareService.revokeSharesBySharer(sharerContactId, "sharer access ended");
                }
            }
        } catch (Exception ex) {
            logger.warn("BeKey share cascade on sub-cancel failed (sub {}): {}", id, ex.getMessage());
        }

        // Notify info@ on every manual cancellation (admin + user self-cancel).
        try {
            String cancelledBy = isAdmin(authentication)
                    ? "ADMIN (" + authentication.getName() + ")"
                    : "USER (" + authentication.getName() + ")";
            contactRepository.findById(sub.getContactId()).ifPresent(c -> {
                String email = c.getEmailPrimary() != null && !c.getEmailPrimary().isBlank()
                        ? c.getEmailPrimary() : c.getEmailSecondary();
                emailService.sendSubscriptionCancellationAdminNotification(
                        c.getName(), email, sub.getDescription(), sub.getCuenta(),
                        sub.getStripeSubscriptionId(), sub.getId(), cancelledBy);
                // Also notify the customer that their subscription was cancelled.
                emailService.sendSubscriptionCancellationToCustomer(email, c.getName(), sub.getDescription());
            });
        } catch (Exception e) {
            logger.warn("Failed to send sub-cancel notification for sub {}: {}", id, e.getMessage());
        }

        // If this was the contact's last active sub, drop tenant_type so the
        // free-booking allowance is removed.
        try {
            Long contactId = sub.getContactId();
            if (contactId != null
                && subscriptionService.findByContactIdAndActiveTrue(contactId).isEmpty()) {
                contactRepository.findById(contactId).ifPresent(c -> {
                    if ("Usuario Virtual".equalsIgnoreCase(c.getTenantType())) {
                        c.setTenantType(null);
                        contactRepository.save(c);
                    }
                });
            }
        } catch (Exception e) {
            logger.warn("Failed to clear tenant_type after sub {} cancel: {}", id, e.getMessage());
        }
    }

    /**
     * Cancels the Stripe subscription and returns its paid-through date
     * (Stripe current_period_end) so the desk can stay occupied until then.
     * Returns null on any failure or when Stripe didn't report a period end.
     */
    private LocalDate cancelStripeSubscription(Subscription sub) {
        try {
            String tenant = "GT".equalsIgnoreCase(sub.getCuenta()) ? "gt" : null;
            String uri = "/api/subscriptions/" + sub.getStripeSubscriptionId();
            if (tenant != null) {
                uri += "?tenant=" + tenant;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> resp = http.delete()
                .uri(uri)
                .retrieve()
                .body(Map.class);
            logger.info("Cancelled Stripe subscription {} for local subscription {}", sub.getStripeSubscriptionId(), sub.getId());
            if (resp != null && resp.get("currentPeriodEnd") != null) {
                long epoch = ((Number) resp.get("currentPeriodEnd")).longValue();
                return java.time.Instant.ofEpochSecond(epoch).atZone(java.time.ZoneOffset.UTC).toLocalDate();
            }
        } catch (Exception e) {
            logger.error("Failed to cancel Stripe subscription {}: {}", sub.getStripeSubscriptionId(), e.getMessage());
        }
        return null;
    }

    private String mapCountryToIso(String country) {
        if (country == null) return "";
        return switch (country.trim().toLowerCase()) {
            case "españa", "spain" -> "ES";
            case "portugal" -> "PT";
            case "francia", "france" -> "FR";
            case "alemania", "germany" -> "DE";
            case "italia", "italy" -> "IT";
            case "reino unido", "united kingdom" -> "GB";
            case "estados unidos", "united states" -> "US";
            default -> country.length() == 2 ? country.toUpperCase() : country;
        };
    }

    private boolean isAdmin(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) return false;
        return authentication.getAuthorities().stream()
            .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
    }

    private static final Set<String> EU_VAT_PREFIXES = Set.of(
        "AT","BE","BG","CY","CZ","DE","DK","EE","EL","ES","FI","FR",
        "HR","HU","IE","IT","LT","LU","LV","MT","NL","PL","PT","RO",
        "SE","SI","SK"
    );

    private static boolean isEuVatNumber(String vatNumber) {
        if (vatNumber == null || vatNumber.length() < 3) return false;
        String prefix = vatNumber.substring(0, 2).toUpperCase();
        return EU_VAT_PREFIXES.contains(prefix);
    }

    private static Integer toInteger(Object value) {
        if (value == null) return null;
        if (value instanceof Integer i) return i;
        if (value instanceof Number n) return n.intValue();
        return null;
    }

    /**
     * One-shot reconciliation: push every active sub's locked vat_percent into
     * Stripe so Stripe stops charging stale rates that no longer match
     * BeWorking's canonical invoices. Run once after V48 lands the lock-in;
     * also useful any time you suspect Stripe-vs-DB drift.
     *
     * Synchronous + throttled (~5 req/sec). For ~322 active subs, ~65 sec.
     * Wrapped in a background thread so it can complete past ALB timeout.
     */
    @PostMapping("/sync-stripe-tax-all")
    public ResponseEntity<?> bulkSyncStripeTax(Authentication authentication) {
        if (!isAdmin(authentication)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        new Thread(() -> {
            long start = System.currentTimeMillis();
            logger.info("Stripe tax bulk sync starting");
            try {
                var stats = subscriptionService.bulkSyncStripeTax();
                long elapsed = (System.currentTimeMillis() - start) / 1000;
                logger.info("Stripe tax bulk sync completed in {}s: {}", elapsed, stats);
            } catch (Exception e) {
                logger.error("Stripe tax bulk sync failed", e);
            }
        }, "stripe-tax-bulk-sync").start();
        return ResponseEntity.accepted().body(Map.of(
            "status", "started",
            "message", "Stripe tax sync running in background. Check backend logs for completion counts."
        ));
    }

    /**
     * Bulk re-lock: recompute vat_percent + push Stripe sync for every active
     * sub. Use this AFTER a reseed completes so freshly-healed vat_valid values
     * propagate to sub.vat_percent (V48/V49 only saw the pre-reseed state).
     */
    @PostMapping("/relock-all")
    public ResponseEntity<?> bulkRelockAllActiveSubs(Authentication authentication) {
        if (!isAdmin(authentication)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        new Thread(() -> {
            long start = System.currentTimeMillis();
            logger.info("Bulk relock starting");
            try {
                var stats = subscriptionService.bulkRelockAllActiveSubs();
                long elapsedMin = (System.currentTimeMillis() - start) / 60_000;
                logger.info("Bulk relock completed in {} min: {}", elapsedMin, stats);
            } catch (Exception e) {
                logger.error("Bulk relock failed", e);
            }
        }, "bulk-relock-all").start();
        return ResponseEntity.accepted().body(Map.of(
            "status", "started",
            "message", "Bulk relock running in background (~2 min for ~321 subs). Check backend logs for completion counts."
        ));
    }

    @PostMapping("/{id}/generate-invoice")
    public ResponseEntity<?> generateInvoice(Authentication authentication, @PathVariable Integer id, @RequestParam String month) {
        if (!isAdmin(authentication)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        Optional<Subscription> opt = subscriptionService.findById(id);
        if (opt.isEmpty()) return ResponseEntity.notFound().build();
        Subscription sub = opt.get();
        if (!"bank_transfer".equals(sub.getBillingMethod())) {
            return ResponseEntity.badRequest().body(Map.of("error", "Not a bank_transfer subscription"));
        }
        Map<String, Object> result = subscriptionService.createBankTransferInvoice(sub, month);
        sub.setLastInvoicedMonth(month);
        subscriptionService.save(sub);
        return ResponseEntity.ok(result);
    }

    /**
     * Upgrade an existing Stripe subscription to a new plan amount.
     * Updates both the Stripe subscription and the local DB record.
     */
    /**
     * Self-service: logged-in free user creates their own subscription.
     * Card must already be on file (via SetupIntent). Charges immediately.
     */
    @PostMapping("/self-subscribe")
    public ResponseEntity<?> selfSubscribe(Authentication authentication, @RequestBody Map<String, Object> body) {
        if (authentication == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();

        String email = authentication.getName();
        String plan = (String) body.getOrDefault("plan", "basic");
        String stripeCustomerId = (String) body.get("stripeCustomerId");
        String paymentMethodId = (String) body.get("paymentMethodId");
        String billingInterval = body.get("billingInterval") != null && !body.get("billingInterval").toString().isBlank()
                ? body.get("billingInterval").toString() : "month";
        // Optional desk assignment (MA1O1-N) for the desk plan; the 30-min desk
        // reconcile grants BeKey access for the linked desk.
        Long selfProductoId = null;
        if (body.get("productoId") != null && !body.get("productoId").toString().isBlank()) {
            try {
                long v = Long.parseLong(body.get("productoId").toString());
                if (v > 0) selfProductoId = v;
            } catch (NumberFormatException ignored) { }
        }

        if (stripeCustomerId == null || stripeCustomerId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "stripeCustomerId is required"));
        }

        // Find contact profile by email
        var contactOpt = contactRepository.findFirstByEmailPrimaryIgnoreCaseOrEmailSecondaryIgnoreCaseOrEmailTertiaryIgnoreCaseOrRepresentativeEmailIgnoreCase(
            email, email, email, email);
        if (contactOpt.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Contact profile not found"));
        }
        ContactProfile contact = contactOpt.get();

        // Check no active subscription already exists
        var existingSubs = subscriptionService.findByContactIdAndActiveTrue(contact.getId());
        if (!existingSubs.isEmpty()) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "Active subscription already exists"));
        }

        // Resolve plan amount
        String planKey = plan.toLowerCase();
        var planOpt = planRepository.findByPlanKey(planKey);
        java.math.BigDecimal amount = planOpt.map(com.beworking.plans.Plan::getPrice)
                .orElse(new java.math.BigDecimal("15.00"));
        String planLabel = planOpt.map(com.beworking.plans.Plan::getName)
                .orElse(planKey.substring(0, 1).toUpperCase() + planKey.substring(1));

        // VAT calculation (same as admin flow)
        String vatNumber = contact.getBillingTaxId();
        String cuenta = "PT";
        String supplierCountry = "ES";
        boolean taxExempt = false;
        if (vatNumber != null && !vatNumber.isBlank()) {
            if (isEuVatNumber(vatNumber)) {
                String prefix = vatNumber.trim().replaceAll("\\s+", "").toUpperCase().substring(0, 2);
                taxExempt = !prefix.equals(supplierCountry);
            } else {
                String countryHint = SubscriptionService.countryNameToIso(contact.getBillingCountry());
                if (countryHint != null) {
                    var viesResult = viesVatService.validate(vatNumber, countryHint);
                    if (viesResult.valid()) {
                        taxExempt = !countryHint.equals(supplierCountry);
                    }
                }
            }
        }
        int vatPercent = taxExempt ? 0 : 21;
        // Per-cycle price = monthly rate × months in the interval (Stripe charges this).
        int intervalMonths = SubscriptionService.monthsForInterval(billingInterval);
        int baseAmountCents = amount.multiply(java.math.BigDecimal.valueOf(intervalMonths))
                .multiply(java.math.BigDecimal.valueOf(100)).intValue();

        // Create Stripe subscription via /api/subscriptions/auto (card already on file)
        try {
            Map<String, Object> stripeRequest = new java.util.HashMap<>();
            stripeRequest.put("customer_email", email);
            stripeRequest.put("customer_name", contact.getName());
            stripeRequest.put("amount_cents", baseAmountCents);
            stripeRequest.put("currency", "eur");
            stripeRequest.put("description", "BeWorking " + planLabel);
            stripeRequest.put("tenant", "bw");
            stripeRequest.put("interval", billingInterval);
            stripeRequest.put("collection_method", "charge_automatically");
            stripeRequest.put("tax_exempt", taxExempt);
            stripeRequest.put("customer_id", stripeCustomerId);
            // SetupIntent PaymentMethod → stripe-service sets it as default before
            // creating the sub (essential for SEPA, mandate not yet in PM list).
            if (paymentMethodId != null && !paymentMethodId.isBlank()) {
                stripeRequest.put("payment_method_id", paymentMethodId);
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> stripeResult = http.post()
                .uri("/api/subscriptions/auto")
                .header("Content-Type", "application/json")
                .body(stripeRequest)
                .retrieve()
                .body(Map.class);

            if (stripeResult == null || !stripeResult.containsKey("subscriptionId")) {
                return ResponseEntity.status(502).body(Map.of("error", "Failed to create Stripe subscription"));
            }

            Subscription sub = new Subscription();
            sub.setContactId(contact.getId());
            sub.setMonthlyAmount(amount);
            sub.setCurrency("EUR");
            sub.setDescription("BeWorking " + planLabel);
            sub.setBillingMethod("stripe");
            sub.setBillingInterval(billingInterval);
            sub.setCuenta(cuenta);
            sub.setStartDate(java.time.LocalDate.now());
            sub.setActive(true);
            sub.setCreatedAt(java.time.LocalDateTime.now());
            sub.setVatNumber(vatNumber);
            sub.setVatPercent(vatPercent);
            sub.setStripeSubscriptionId((String) stripeResult.get("subscriptionId"));
            sub.setStripeCustomerId((String) stripeResult.get("customerId"));
            sub.setProductoId(selfProductoId);
            subscriptionService.save(sub);

            // Promote contact to "Usuario Virtual" so booking-usage grants the
            // 5-free-bookings/month allowance.
            try {
                contact.setTenantType("Usuario Virtual");
                contactRepository.save(contact);
            } catch (Exception e) {
                logger.warn("Failed to set tenant_type=Usuario Virtual on contact {}: {}", contact.getId(), e.getMessage());
            }

            // Create local invoice
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
                } catch (Exception e) {
                    logger.warn("Failed to create local invoice: {}", e.getMessage());
                }
            }

            // Notify admin (info@be-working.com). Self-flow customer already knows
            // their password — skip welcome-with-password-setup (sent only from admin flow).
            try {
                emailService.sendSubscriptionAdminNotification(
                        contact.getName(),
                        email,
                        sub.getDescription(),
                        amount.toPlainString(),
                        sub.getCurrency(),
                        sub.getBillingInterval(),
                        sub.getCuenta(),
                        sub.getStripeSubscriptionId());
            } catch (Exception e) {
                logger.warn("Failed to send admin notification for self-subscribe {}: {}", sub.getId(), e.getMessage());
            }

            return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "message", "Subscription created",
                "subscriptionId", sub.getId(),
                "plan", planLabel
            ));
        } catch (Exception e) {
            logger.error("Self-subscribe failed: {}", e.getMessage(), e);
            return ResponseEntity.status(502).body(Map.of("error", "Payment failed: " + e.getMessage()));
        }
    }

    @PostMapping("/{id}/upgrade")
    public ResponseEntity<?> upgradePlan(Authentication authentication, @PathVariable Integer id, @RequestBody Map<String, Object> body) {
        if (authentication == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        Optional<Subscription> opt = subscriptionService.findById(id);
        if (opt.isEmpty()) return ResponseEntity.notFound().build();
        Subscription sub = opt.get();

        // Admins can edit any sub to any amount. A non-admin may change their OWN
        // sub, but the price must come from a real plan (never a client-supplied
        // amount — a user could otherwise set €0).
        boolean admin = isAdmin(authentication);
        if (!admin) {
            // Verify ownership via the user's tenant_id (= contact id), NOT by
            // matching the login email against the contact's email fields — a
            // user's login email isn't always one of the contact emails, which
            // would wrongly 403 (contact↔login email drift).
            Long myContactId = userRepository.findByEmail(authentication.getName())
                    .map(User::getTenantId).orElse(null);
            if (myContactId == null || !myContactId.equals(sub.getContactId())) {
                logger.warn("Self-upgrade forbidden: user={} myContactId={} subContactId={} subId={}",
                        authentication.getName(), myContactId, sub.getContactId(), id);
                return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
            }
        }

        BigDecimal newAmount;
        String newDescription;
        if (admin) {
            newAmount = new BigDecimal(body.get("monthlyAmount").toString());
            newDescription = body.get("description") != null ? body.get("description").toString() : sub.getDescription();
        } else {
            // Self-service: derive the price from the requested plan key, server-side.
            String planKey = body.get("plan") != null ? body.get("plan").toString().toLowerCase().trim() : "";
            var planOpt = planRepository.findByPlanKey(planKey);
            if (planOpt.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Unknown plan"));
            }
            newAmount = planOpt.get().getPrice();
            newDescription = "Oficina Virtual " + planOpt.get().getName();
        }
        // Optional interval change (month/quarter/half_year/year). Default: keep the
        // sub's current interval. monthly_amount stays the MONTHLY rate; the Stripe
        // price is monthly × months-in-cycle.
        String newInterval = body.get("billingInterval") != null && !body.get("billingInterval").toString().isBlank()
                ? body.get("billingInterval").toString()
                : (sub.getBillingInterval() != null ? sub.getBillingInterval() : "month");
        int intervalMonths = SubscriptionService.monthsForInterval(newInterval);

        // Optional new billing date (yyyy-MM-dd). Moves the billing anchor: Stripe
        // pauses billing until this date (trial_end) and re-anchors there. No
        // proration. Must be in the future.
        java.time.LocalDate newBillingDate = null;
        Long trialEndEpoch = null;
        if (admin && body.get("billingDate") != null && !body.get("billingDate").toString().isBlank()) {
            newBillingDate = java.time.LocalDate.parse(body.get("billingDate").toString());
            if (!newBillingDate.isAfter(java.time.LocalDate.now())) {
                return ResponseEntity.badRequest().body(Map.of("error", "billingDate must be a future date"));
            }
            trialEndEpoch = newBillingDate.atStartOfDay(java.time.ZoneId.of("Europe/Madrid")).toEpochSecond();
        }

        // Update Stripe subscription if it exists
        if (sub.getStripeSubscriptionId() != null && !sub.getStripeSubscriptionId().isBlank()
                && !sub.getStripeSubscriptionId().startsWith("sub_sched_")) {
            try {
                String tenant = "GT".equalsIgnoreCase(sub.getCuenta()) ? "gt" : "bw";

                // Send the NET per-cycle amount (monthly × months) to Stripe.
                // update-amount preserves the sub's default_tax_rate, so Stripe
                // layers VAT once on top, and applies the change at the next
                // renewal (proration_behavior=none).
                int amountCents = newAmount.multiply(BigDecimal.valueOf(intervalMonths))
                        .multiply(BigDecimal.valueOf(100)).intValue();

                Map<String, Object> updateBody = new HashMap<>();
                updateBody.put("amount_cents", amountCents);
                updateBody.put("tenant", tenant);
                updateBody.put("description", newDescription);
                updateBody.put("interval", newInterval);
                if (trialEndEpoch != null) {
                    updateBody.put("trial_end", trialEndEpoch);
                }

                http.post()
                    .uri("/api/subscriptions/" + sub.getStripeSubscriptionId() + "/update-amount")
                    .header("Content-Type", "application/json")
                    .body(updateBody)
                    .retrieve()
                    .toBodilessEntity();

                logger.info("Upgraded Stripe subscription {} to {} cents (interval={}, billingDate={})",
                        sub.getStripeSubscriptionId(), amountCents, newInterval, newBillingDate);
            } catch (Exception e) {
                logger.error("Failed to upgrade Stripe subscription: {}", e.getMessage());
                return ResponseEntity.status(502).body(Map.of("error", "Failed to update Stripe: " + e.getMessage()));
            }
        }

        // Optional desk assignment (productoId). Links the sub to a specific desk
        // slot (MA1O1-N) so the floor plan shows it occupied and the 30-min desk
        // reconcile grants BeKey access. "0" / empty clears the assignment.
        if (body.containsKey("productoId")) {
            Object raw = body.get("productoId");
            Long productoId = null;
            if (raw != null && !raw.toString().isBlank()) {
                try {
                    long v = Long.parseLong(raw.toString());
                    if (v > 0) productoId = v;
                } catch (NumberFormatException ignored) { }
            }
            sub.setProductoId(productoId);
        }

        // Update local record (monthly_amount = monthly rate; interval tracked separately)
        sub.setMonthlyAmount(newAmount);
        sub.setDescription(newDescription);
        sub.setBillingInterval(newInterval);
        if (newBillingDate != null) {
            sub.setStartDate(newBillingDate);
        }
        sub.setUpdatedAt(java.time.LocalDateTime.now());
        subscriptionService.save(sub);

        return ResponseEntity.ok(Map.of("message", "Plan upgraded", "newAmount", newAmount, "interval", newInterval));
    }
}
