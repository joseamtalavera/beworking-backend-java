package com.beworking.subscriptions;

import com.beworking.auth.User;
import com.beworking.auth.UserRepository;
import com.beworking.contacts.ContactProfile;
import com.beworking.contacts.ContactProfileRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
    private final RestClient http;

    public SubscriptionController(SubscriptionService subscriptionService,
                                  UserRepository userRepository,
                                  ContactProfileRepository contactRepository,
                                  @Value("${app.payments.base-url:http://beworking-stripe-service:8081}") String paymentsBaseUrl) {
        this.subscriptionService = subscriptionService;
        this.userRepository = userRepository;
        this.contactRepository = contactRepository;
        this.http = RestClient.builder().baseUrl(paymentsBaseUrl).build();
    }

    @GetMapping
    public ResponseEntity<?> list(
        Authentication authentication,
        @RequestParam(required = false) Long contactId
    ) {
        if (!isAdmin(authentication)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
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

        String stripeSubId = request.getStripeSubscriptionId();

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
                int amountCents = request.getMonthlyAmount()
                    .multiply(BigDecimal.valueOf(100)).intValue();

                Map<String, Object> stripeRequest = new HashMap<>();
                stripeRequest.put("customer_email", email);
                stripeRequest.put("customer_name", contact.getName());
                stripeRequest.put("amount_cents", amountCents);
                stripeRequest.put("currency", request.getCurrency() != null ? request.getCurrency().toLowerCase() : "eur");
                stripeRequest.put("description", request.getDescription() != null ? request.getDescription() : "Oficina Virtual");

                // VAT: if no VAT number → apply 21% tax in Stripe; if VAT number → tax exempt
                boolean taxExempt = request.getVatNumber() != null && !request.getVatNumber().isBlank();
                stripeRequest.put("vat_number", taxExempt ? request.getVatNumber() : "");
                stripeRequest.put("tax_exempt", taxExempt);

                // Anchor billing to the 1st of next month (prorate the first partial period)
                LocalDate firstOfNextMonth = LocalDate.now().plusMonths(1).withDayOfMonth(1);
                long anchorEpoch = firstOfNextMonth.atStartOfDay().toEpochSecond(ZoneOffset.UTC);
                stripeRequest.put("billing_cycle_anchor", anchorEpoch);

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
                Map<String, Object> stripeResponse = http.post()
                    .uri("/api/subscriptions/auto")
                    .header("Content-Type", "application/json")
                    .body(stripeRequest)
                    .retrieve()
                    .body(Map.class);

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

        Subscription sub = new Subscription();
        sub.setContactId(request.getContactId());
        sub.setStripeSubscriptionId(request.getStripeSubscriptionId());
        sub.setStripeCustomerId(request.getStripeCustomerId());
        sub.setMonthlyAmount(request.getMonthlyAmount());
        sub.setCurrency(request.getCurrency() != null ? request.getCurrency() : "EUR");
        sub.setCuenta(request.getCuenta() != null ? request.getCuenta() : "PT");
        sub.setDescription(request.getDescription() != null ? request.getDescription() : "Oficina Virtual");
        boolean hasVatNumber = request.getVatNumber() != null && !request.getVatNumber().isBlank();
        sub.setVatNumber(hasVatNumber ? request.getVatNumber() : null);
        sub.setVatPercent(hasVatNumber ? 0 : (request.getVatPercent() != null ? request.getVatPercent() : 21));
        sub.setStartDate(request.getStartDate() != null ? request.getStartDate() : LocalDate.now());
        sub.setEndDate(request.getEndDate());
        sub.setActive(true);
        sub.setCreatedAt(LocalDateTime.now());
        sub.setUpdatedAt(LocalDateTime.now());

        Subscription saved = subscriptionService.save(sub);
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
        if (request.getCuenta() != null) sub.setCuenta(request.getCuenta());
        if (request.getDescription() != null) sub.setDescription(request.getDescription());
        if (request.getMonthlyAmount() != null) sub.setMonthlyAmount(request.getMonthlyAmount());
        if (request.getVatPercent() != null) sub.setVatPercent(request.getVatPercent());
        if (request.getEndDate() != null) sub.setEndDate(request.getEndDate());
        if (request.getActive() != null) {
            sub.setActive(request.getActive());
            if (!request.getActive() && sub.getEndDate() == null) {
                sub.setEndDate(LocalDate.now());
            }
        }
        sub.setUpdatedAt(LocalDateTime.now());

        Subscription saved = subscriptionService.save(sub);
        return ResponseEntity.ok(saved);
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

        subscriptionService.deactivate(id);
        return ResponseEntity.noContent().build();
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
        Optional<User> userOpt = userRepository.findByEmail(authentication.getName());
        return userOpt.isPresent() && userOpt.get().getRole() == User.Role.ADMIN;
    }
}
