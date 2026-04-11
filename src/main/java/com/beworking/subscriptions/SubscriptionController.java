package com.beworking.subscriptions;

import com.beworking.auth.User;
import com.beworking.auth.UserRepository;
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
    private final RestClient http;

    public SubscriptionController(SubscriptionService subscriptionService,
                                  UserRepository userRepository,
                                  ContactProfileRepository contactRepository,
                                  ProductoRepository productoRepository,
                                  com.beworking.plans.PlanRepository planRepository,
                                  com.beworking.contacts.ViesVatService viesVatService,
                                  @Value("${app.payments.base-url:http://beworking-stripe-service:8081}") String paymentsBaseUrl) {
        this.subscriptionService = subscriptionService;
        this.userRepository = userRepository;
        this.contactRepository = contactRepository;
        this.productoRepository = productoRepository;
        this.planRepository = planRepository;
        this.viesVatService = viesVatService;
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

        // --- Bank transfer flow: no Stripe interaction ---
        if ("bank_transfer".equals(billingMethod)) {
            // Resolve VAT number from contact profile if not explicitly provided
            String vatNumber = request.getVatNumber();
            if (vatNumber == null || vatNumber.isBlank()) {
                Optional<ContactProfile> contactOpt = contactRepository.findById(request.getContactId());
                if (contactOpt.isPresent() && contactOpt.get().getBillingTaxId() != null) {
                    vatNumber = contactOpt.get().getBillingTaxId();
                }
            }

            Subscription sub = new Subscription();
            sub.setContactId(request.getContactId());
            sub.setBillingMethod("bank_transfer");
            sub.setMonthlyAmount(request.getMonthlyAmount());
            sub.setCurrency(request.getCurrency() != null ? request.getCurrency() : "EUR");
            sub.setCuenta(request.getCuenta() != null ? request.getCuenta() : "GT");
            sub.setDescription(request.getDescription() != null ? request.getDescription() : "Oficina Virtual");
            sub.setBillingInterval(request.getBillingInterval() != null ? request.getBillingInterval() : "month");
            boolean hasVat = vatNumber != null && !vatNumber.isBlank();
            sub.setVatNumber(hasVat ? vatNumber : null);
            boolean euIntra = isEuVatNumber(vatNumber);
            sub.setVatPercent(euIntra ? 0 : (request.getVatPercent() != null ? request.getVatPercent() : 21));
            sub.setStartDate(request.getStartDate() != null ? request.getStartDate() : LocalDate.now());
            sub.setEndDate(request.getEndDate());
            sub.setProductoId(request.getProductoId());
            sub.setActive(true);
            sub.setCreatedAt(LocalDateTime.now());
            sub.setUpdatedAt(LocalDateTime.now());

            Subscription saved = subscriptionService.save(sub);

            // Create first Pendiente invoice for the start month
            try {
                String month = saved.getStartDate().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM"));
                subscriptionService.createBankTransferInvoice(saved, month);
                saved.setLastInvoicedMonth(month);
                subscriptionService.save(saved);
                logger.info("Created first Pendiente invoice for bank_transfer subscription {} (month={})", saved.getId(), month);
            } catch (Exception e) {
                logger.warn("Failed to create first bank_transfer invoice: {}", e.getMessage(), e);
            }

            return ResponseEntity.status(HttpStatus.CREATED).body(saved);
        }

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

        // If no Stripe subscription ID provided, create one via stripe-service
        if (stripeSubId == null || stripeSubId.isBlank()) {
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

                // Calculate amount including VAT for Stripe
                BigDecimal baseAmount = request.getMonthlyAmount();
                int vatPercent = taxExempt ? 0 : (request.getVatPercent() != null ? request.getVatPercent() : 21);
                BigDecimal vatAmount = baseAmount.multiply(BigDecimal.valueOf(vatPercent))
                    .divide(BigDecimal.valueOf(100), 2, java.math.RoundingMode.HALF_UP);
                BigDecimal totalAmount = baseAmount.add(vatAmount);
                int amountCents = totalAmount.multiply(BigDecimal.valueOf(100)).intValue();

                logger.info("Creating Stripe sub: base={} vat={}% vatAmount={} total={} taxExempt={}",
                    baseAmount, vatPercent, vatAmount, totalAmount, taxExempt);

                Map<String, Object> stripeRequest = new HashMap<>();
                stripeRequest.put("customer_email", email);
                stripeRequest.put("customer_name", contact.getName());
                stripeRequest.put("amount_cents", amountCents);
                stripeRequest.put("currency", request.getCurrency() != null ? request.getCurrency().toLowerCase() : "eur");
                stripeRequest.put("description", request.getDescription() != null ? request.getDescription() : "Oficina Virtual");
                stripeRequest.put("tenant", "GT".equalsIgnoreCase(request.getCuenta()) ? "gt" : "bw");

                // Reuse existing Stripe customer for this contact if available
                subscriptionService.findByContactIdAndActiveTrue(request.getContactId()).stream()
                    .filter(s -> s.getStripeCustomerId() != null && !s.getStripeCustomerId().isBlank())
                    .findFirst()
                    .ifPresent(s -> stripeRequest.put("customer_id", s.getStripeCustomerId()));

                stripeRequest.put("vat_number", taxExempt ? resolvedVat : "");
                stripeRequest.put("tax_exempt", taxExempt);

                // Anchor billing to the requested start date
                LocalDate startDate = request.getStartDate() != null ? request.getStartDate() : LocalDate.now();
                boolean isFuture = startDate.isAfter(LocalDate.now());

                if (isFuture) {
                    // Future start: use Stripe subscription schedules (handled by stripe-service)
                    long anchorEpoch = startDate.atStartOfDay().toEpochSecond(ZoneOffset.UTC);
                    stripeRequest.put("billing_cycle_anchor", anchorEpoch);
                    stripeRequest.put("proration_behavior", "none");
                } else {
                    // Start now or in the past: anchor to 1st of next month, prorate
                    LocalDate firstOfNextMonth = LocalDate.now().plusMonths(1).withDayOfMonth(1);
                    long anchorEpoch = firstOfNextMonth.atStartOfDay().toEpochSecond(ZoneOffset.UTC);
                    stripeRequest.put("billing_cycle_anchor", anchorEpoch);
                    stripeRequest.put("proration_behavior", "create_prorations");
                }

                // Pass billing interval (month, quarter, year)
                String interval = request.getBillingInterval() != null ? request.getBillingInterval() : "month";
                stripeRequest.put("interval", interval);

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

        // Check for duplicate stripe subscription ID
        if (subscriptionService.findByStripeSubscriptionId(stripeSubId).isPresent()) {
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
        sub.setBillingMethod("stripe");
        sub.setStripeSubscriptionId(request.getStripeSubscriptionId());
        sub.setStripeCustomerId(request.getStripeCustomerId());
        sub.setMonthlyAmount(request.getMonthlyAmount());
        sub.setCurrency(request.getCurrency() != null ? request.getCurrency() : "EUR");
        sub.setCuenta(request.getCuenta() != null ? request.getCuenta() : "PT");
        sub.setDescription(request.getDescription() != null ? request.getDescription() : "Oficina Virtual");
        sub.setBillingInterval(request.getBillingInterval() != null ? request.getBillingInterval() : "month");
        boolean hasVatNumber = effectiveVat != null && !effectiveVat.isBlank();
        sub.setVatNumber(hasVatNumber ? effectiveVat : null);
        boolean euIntracommunity = isEuVatNumber(effectiveVat);
        sub.setVatPercent(euIntracommunity ? 0 : (request.getVatPercent() != null ? request.getVatPercent() : 21));
        sub.setStartDate(request.getStartDate() != null ? request.getStartDate() : LocalDate.now());
        sub.setEndDate(request.getEndDate());
        sub.setProductoId(request.getProductoId());
        sub.setActive(true);
        sub.setCreatedAt(LocalDateTime.now());
        sub.setUpdatedAt(LocalDateTime.now());

        Subscription saved = subscriptionService.save(sub);

        // Create local invoices from Stripe data
        if (stripeResponse != null && stripeResponse.containsKey("firstInvoice")) {
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
        if (request.getStripeSubscriptionId() != null) sub.setStripeSubscriptionId(request.getStripeSubscriptionId());
        if (request.getStripeCustomerId() != null) sub.setStripeCustomerId(request.getStripeCustomerId());
        if (request.getCuenta() != null) sub.setCuenta(request.getCuenta());
        if (request.getDescription() != null) sub.setDescription(request.getDescription());
        if (request.getMonthlyAmount() != null) sub.setMonthlyAmount(request.getMonthlyAmount());
        if (request.getVatPercent() != null) sub.setVatPercent(request.getVatPercent());
        if (request.getEndDate() != null) sub.setEndDate(request.getEndDate());
        if (request.getProductoId() != null) sub.setProductoId(request.getProductoId());
        if (request.getActive() != null) {
            sub.setActive(request.getActive());
            if (!request.getActive() && sub.getEndDate() == null) {
                sub.setEndDate(LocalDate.now());
            }
            // Cancel in Stripe when deactivating
            if (!request.getActive() && sub.getStripeSubscriptionId() != null && !sub.getStripeSubscriptionId().isBlank()) {
                cancelStripeSubscription(sub);
            }
        }
        sub.setUpdatedAt(LocalDateTime.now());

        Subscription saved = subscriptionService.save(sub);
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

    @DeleteMapping("/{id}")
    public ResponseEntity<?> deactivate(Authentication authentication, @PathVariable Integer id) {
        if (!isAdmin(authentication)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        Optional<Subscription> subOpt = subscriptionService.findById(id);
        if (subOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        Subscription sub = subOpt.get();
        if (sub.getStripeSubscriptionId() != null && !sub.getStripeSubscriptionId().isBlank()) {
            cancelStripeSubscription(sub);
        }
        subscriptionService.deactivate(id);
        return ResponseEntity.noContent().build();
    }

    private void cancelStripeSubscription(Subscription sub) {
        try {
            String tenant = "GT".equalsIgnoreCase(sub.getCuenta()) ? "gt" : null;
            String uri = "/api/subscriptions/" + sub.getStripeSubscriptionId();
            if (tenant != null) {
                uri += "?tenant=" + tenant;
            }
            http.delete()
                .uri(uri)
                .retrieve()
                .toBodilessEntity();
            logger.info("Cancelled Stripe subscription {} for local subscription {}", sub.getStripeSubscriptionId(), sub.getId());
        } catch (Exception e) {
            logger.error("Failed to cancel Stripe subscription {}: {}", sub.getStripeSubscriptionId(), e.getMessage());
        }
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
        "AT","BE","BG","CY","CZ","DE","DK","EE","ES","FI","FR","GR",
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

    @PostMapping("/{id}/generate-invoice")
    public ResponseEntity<?> generateInvoice(@PathVariable Integer id, @RequestParam String month) {
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
        int baseAmountCents = amount.multiply(java.math.BigDecimal.valueOf(100)).intValue();

        // Create Stripe subscription via /api/subscriptions/auto (card already on file)
        try {
            Map<String, Object> stripeRequest = new java.util.HashMap<>();
            stripeRequest.put("customer_email", email);
            stripeRequest.put("customer_name", contact.getName());
            stripeRequest.put("amount_cents", baseAmountCents);
            stripeRequest.put("currency", "eur");
            stripeRequest.put("description", "BeWorking " + planLabel);
            stripeRequest.put("tenant", "bw");
            stripeRequest.put("collection_method", "charge_automatically");
            stripeRequest.put("tax_exempt", taxExempt);
            stripeRequest.put("customer_id", stripeCustomerId);

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
            sub.setCuenta(cuenta);
            sub.setStartDate(java.time.LocalDate.now());
            sub.setActive(true);
            sub.setCreatedAt(java.time.LocalDateTime.now());
            sub.setVatNumber(vatNumber);
            sub.setVatPercent(vatPercent);
            sub.setStripeSubscriptionId((String) stripeResult.get("subscriptionId"));
            sub.setStripeCustomerId((String) stripeResult.get("customerId"));
            subscriptionService.save(sub);

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
    public ResponseEntity<?> upgradePlan(@PathVariable Integer id, @RequestBody Map<String, Object> body) {
        Optional<Subscription> opt = subscriptionService.findById(id);
        if (opt.isEmpty()) return ResponseEntity.notFound().build();
        Subscription sub = opt.get();

        BigDecimal newAmount = new BigDecimal(body.get("monthlyAmount").toString());
        String newDescription = body.get("description") != null ? body.get("description").toString() : sub.getDescription();

        // Update Stripe subscription if it exists
        if (sub.getStripeSubscriptionId() != null && !sub.getStripeSubscriptionId().isBlank()
                && !sub.getStripeSubscriptionId().startsWith("sub_sched_")) {
            try {
                String tenant = "GT".equalsIgnoreCase(sub.getCuenta()) ? "gt" : "bw";

                // Resolve VAT
                int vatPercent = sub.getVatPercent() != null ? sub.getVatPercent() : 21;
                BigDecimal totalAmount = newAmount.add(newAmount.multiply(BigDecimal.valueOf(vatPercent)).divide(BigDecimal.valueOf(100), 2, java.math.RoundingMode.HALF_UP));
                int amountCents = totalAmount.multiply(BigDecimal.valueOf(100)).intValue();

                http.post()
                    .uri("/api/subscriptions/" + sub.getStripeSubscriptionId() + "/update-amount")
                    .header("Content-Type", "application/json")
                    .body(Map.of("amount_cents", amountCents, "tenant", tenant, "description", newDescription))
                    .retrieve()
                    .toBodilessEntity();

                logger.info("Upgraded Stripe subscription {} to {} cents", sub.getStripeSubscriptionId(), amountCents);
            } catch (Exception e) {
                logger.error("Failed to upgrade Stripe subscription: {}", e.getMessage());
                return ResponseEntity.status(502).body(Map.of("error", "Failed to update Stripe: " + e.getMessage()));
            }
        }

        // Update local record
        sub.setMonthlyAmount(newAmount);
        sub.setDescription(newDescription);
        sub.setUpdatedAt(java.time.LocalDateTime.now());
        subscriptionService.save(sub);

        return ResponseEntity.ok(Map.of("message", "Plan upgraded", "newAmount", newAmount));
    }
}
