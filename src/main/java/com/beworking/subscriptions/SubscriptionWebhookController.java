package com.beworking.subscriptions;

import com.beworking.cuentas.Cuenta;
import com.beworking.cuentas.CuentaService;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/webhooks")
public class SubscriptionWebhookController {

    private static final Logger logger = LoggerFactory.getLogger(SubscriptionWebhookController.class);

    private final SubscriptionService subscriptionService;
    private final CuentaService cuentaService;

    @Value("${app.webhook.callback-secret:}")
    private String callbackSecret;

    public SubscriptionWebhookController(SubscriptionService subscriptionService, CuentaService cuentaService) {
        this.subscriptionService = subscriptionService;
        this.cuentaService = cuentaService;
    }

    @PostMapping("/subscription-invoice")
    public ResponseEntity<Map<String, Object>> handleSubscriptionInvoice(
        @RequestBody SubscriptionInvoicePayload payload,
        @RequestHeader(value = "X-Callback-Secret", required = false) String secret
    ) {
        if (callbackSecret != null && !callbackSecret.isBlank()) {
            if (secret == null || !secret.equals(callbackSecret)) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
            }
        }

        String subId = payload.getStripeSubscriptionId();
        if (subId == null || subId.isBlank()) {
            Map<String, Object> error = new HashMap<>();
            error.put("error", "stripeSubscriptionId is required");
            return ResponseEntity.badRequest().body(error);
        }

        logger.info("Subscription invoice webhook — subscriptionId={} stripeInvoiceId={} status={}",
            subId, payload.getStripeInvoiceId(), payload.getStatus());

        Optional<Subscription> subOpt = subscriptionService.findByStripeSubscriptionId(subId);

        // Fallback: if not found by subscription ID, try matching by Stripe customer ID.
        // This handles cases where subscriptions were created directly in Stripe
        // (e.g. as subscription schedules) and the local record has a different ID.
        if (subOpt.isEmpty() && payload.getStripeCustomerId() != null && !payload.getStripeCustomerId().isBlank()) {
            subOpt = subscriptionService.findByStripeCustomerId(payload.getStripeCustomerId());
            if (subOpt.isPresent()) {
                Subscription matched = subOpt.get();
                logger.info("Matched subscription by stripeCustomerId={} — updating stripeSubscriptionId from {} to {}",
                    payload.getStripeCustomerId(), matched.getStripeSubscriptionId(), subId);
                matched.setStripeSubscriptionId(subId);
                subscriptionService.save(matched);
            }
        }

        if (subOpt.isEmpty()) {
            logger.warn("No subscription found for stripeSubscriptionId={} or stripeCustomerId={}", subId, payload.getStripeCustomerId());
            Map<String, Object> error = new HashMap<>();
            error.put("error", "Subscription not found: " + subId);
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error);
        }

        Subscription subscription = subOpt.get();
        if (!subscription.getActive()) {
            logger.warn("Subscription {} is inactive, skipping invoice creation", subId);
            Map<String, Object> error = new HashMap<>();
            error.put("error", "Subscription is inactive");
            return ResponseEntity.status(HttpStatus.GONE).body(error);
        }

        Map<String, Object> result = subscriptionService.createInvoiceFromSubscription(subscription, payload);

        if (result == null) {
            Map<String, Object> response = new HashMap<>();
            response.put("message", "Invoice already exists for this Stripe invoice");
            response.put("stripeInvoiceId", payload.getStripeInvoiceId());
            return ResponseEntity.ok(response);
        }

        return ResponseEntity.status(HttpStatus.CREATED).body(result);
    }

    @PostMapping("/subscription-activated")
    public ResponseEntity<Map<String, Object>> subscriptionActivated(
        @RequestBody Map<String, String> payload,
        @RequestHeader(value = "X-Callback-Secret", required = false) String secret
    ) {
        if (callbackSecret != null && !callbackSecret.isBlank()) {
            if (secret == null || !secret.equals(callbackSecret)) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
            }
        }

        String scheduleId = payload.get("scheduleId");
        String subscriptionId = payload.get("subscriptionId");
        String customerId = payload.get("customerId");

        if (scheduleId == null || scheduleId.isBlank() || subscriptionId == null || subscriptionId.isBlank()) {
            Map<String, Object> error = new HashMap<>();
            error.put("error", "scheduleId and subscriptionId are required");
            return ResponseEntity.badRequest().body(error);
        }

        logger.info("Subscription activated webhook — scheduleId={} subscriptionId={} customerId={}",
            scheduleId, subscriptionId, customerId);

        Optional<Subscription> subOpt = subscriptionService.findByStripeSubscriptionId(scheduleId);
        if (subOpt.isEmpty()) {
            logger.warn("No subscription found for scheduleId={}", scheduleId);
            Map<String, Object> error = new HashMap<>();
            error.put("error", "Subscription not found for schedule: " + scheduleId);
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error);
        }

        Subscription subscription = subOpt.get();
        subscription.setStripeSubscriptionId(subscriptionId);
        if (customerId != null && !customerId.isBlank()) {
            subscription.setStripeCustomerId(customerId);
        }
        subscriptionService.save(subscription);

        logger.info("Updated subscription id={} — stripeSubscriptionId {} → {}, customerId={}",
            subscription.getId(), scheduleId, subscriptionId, customerId);

        Map<String, Object> response = new HashMap<>();
        response.put("id", subscription.getId());
        response.put("oldStripeId", scheduleId);
        response.put("newStripeId", subscriptionId);
        response.put("customerId", customerId);
        return ResponseEntity.ok(response);
    }

    /**
     * Stripe customer.subscription.deleted relay — LOG ONLY.
     *
     * <p>Per product rule, DB subscription cancellation is manual-only and contact
     * Activo→Inactivo demotion is owned exclusively by {@code ActivoAgingScheduler}
     * (no factura in 12 months AND no active sub). This handler therefore does NOT
     * mutate the local Subscription row or contact_profiles.status. It records the
     * Stripe cancellation in the application log for audit, and returns 200 so the
     * stripe-service relay treats the delivery as processed.
     */
    @PostMapping("/subscription-cancelled")
    public ResponseEntity<Map<String, Object>> subscriptionCancelled(
        @RequestBody Map<String, String> payload,
        @RequestHeader(value = "X-Callback-Secret", required = false) String secret
    ) {
        if (callbackSecret != null && !callbackSecret.isBlank()) {
            if (secret == null || !secret.equals(callbackSecret)) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
            }
        }

        String subscriptionId = payload.get("subscriptionId");
        String customerId = payload.get("customerId");

        if (subscriptionId == null || subscriptionId.isBlank()) {
            Map<String, Object> error = new HashMap<>();
            error.put("error", "subscriptionId is required");
            return ResponseEntity.badRequest().body(error);
        }

        Optional<Subscription> subOpt = subscriptionService.findByStripeSubscriptionId(subscriptionId);
        if (subOpt.isEmpty() && customerId != null && !customerId.isBlank()) {
            subOpt = subscriptionService.findByStripeCustomerId(customerId);
        }

        Map<String, Object> response = new HashMap<>();
        response.put("stripeSubscriptionId", subscriptionId);
        response.put("customerId", customerId);
        response.put("mutated", false);

        if (subOpt.isEmpty()) {
            logger.info("subscription-cancelled (log-only) — no local sub matched subscriptionId={} customerId={}",
                subscriptionId, customerId);
            response.put("matched", false);
            return ResponseEntity.ok(response);
        }

        Subscription subscription = subOpt.get();
        logger.info("subscription-cancelled (log-only) — Stripe cancelled subId={} customerId={} localSubId={} contactId={}. "
            + "DB sub NOT deactivated; contact status NOT changed. Manual review required.",
            subscriptionId, customerId, subscription.getId(), subscription.getContactId());
        response.put("matched", true);
        response.put("localSubscriptionId", subscription.getId());
        response.put("contactId", subscription.getContactId());
        return ResponseEntity.ok(response);
    }

    @PostMapping("/subscription-trial-converted")
    public ResponseEntity<Map<String, Object>> trialConverted(
        @RequestBody Map<String, String> payload,
        @RequestHeader(value = "X-Callback-Secret", required = false) String secret
    ) {
        if (callbackSecret != null && !callbackSecret.isBlank()) {
            if (secret == null || !secret.equals(callbackSecret)) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
            }
        }

        String subscriptionId = payload.get("subscriptionId");
        String customerId = payload.get("customerId");

        if (subscriptionId == null || subscriptionId.isBlank()) {
            Map<String, Object> error = new HashMap<>();
            error.put("error", "subscriptionId is required");
            return ResponseEntity.badRequest().body(error);
        }

        Optional<Subscription> subOpt = subscriptionService.findByStripeSubscriptionId(subscriptionId);
        if (subOpt.isEmpty() && customerId != null && !customerId.isBlank()) {
            subOpt = subscriptionService.findByStripeCustomerId(customerId);
        }

        if (subOpt.isEmpty()) {
            logger.warn("trial-converted: no subscription found for subscriptionId={} customerId={}", subscriptionId, customerId);
            Map<String, Object> error = new HashMap<>();
            error.put("error", "Subscription not found");
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error);
        }

        Subscription subscription = subOpt.get();
        Long contactId = subscription.getContactId();

        int updated = subscriptionService.updateContactStatusIfTrial(contactId, "Activo");
        logger.info("Trial converted — subscriptionId={} contactId={} rows updated={}", subscriptionId, contactId, updated);

        Map<String, Object> response = new HashMap<>();
        response.put("contactId", contactId);
        response.put("status", "Activo");
        response.put("updated", updated);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/reserve-invoice-number")
    public ResponseEntity<Map<String, Object>> reserveInvoiceNumber(
        @RequestParam String stripeSubscriptionId,
        @RequestParam(required = false) String stripeCustomerId,
        @RequestHeader(value = "X-Callback-Secret", required = false) String secret
    ) {
        if (callbackSecret != null && !callbackSecret.isBlank()) {
            if (secret == null || !secret.equals(callbackSecret)) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
            }
        }

        Optional<Subscription> subOpt = subscriptionService.findByStripeSubscriptionId(stripeSubscriptionId);

        if (subOpt.isEmpty() && stripeCustomerId != null && !stripeCustomerId.isBlank()) {
            subOpt = subscriptionService.findByStripeCustomerId(stripeCustomerId);
        }

        if (subOpt.isEmpty()) {
            logger.warn("reserve-invoice-number: no subscription found for stripeSubscriptionId={} stripeCustomerId={}",
                stripeSubscriptionId, stripeCustomerId);
            Map<String, Object> error = new HashMap<>();
            error.put("error", "Subscription not found");
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error);
        }

        Subscription subscription = subOpt.get();
        String cuentaCodigo = subscription.getCuenta();

        String invoiceNumber;
        Optional<Cuenta> cuentaOpt = cuentaService.getCuentaByCodigo(cuentaCodigo);
        if (cuentaOpt.isPresent()) {
            invoiceNumber = cuentaService.generateNextInvoiceNumber(cuentaOpt.get().getId());
        } else {
            invoiceNumber = cuentaService.generateNextInvoiceNumber("PT");
        }

        logger.info("Reserved invoice number {} for subscription {} (cuenta={})",
            invoiceNumber, stripeSubscriptionId, cuentaCodigo);

        Map<String, Object> response = new HashMap<>();
        response.put("invoiceNumber", invoiceNumber);
        response.put("cuenta", cuentaCodigo);
        return ResponseEntity.ok(response);
    }
}
