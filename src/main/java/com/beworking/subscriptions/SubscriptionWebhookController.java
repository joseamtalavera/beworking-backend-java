package com.beworking.subscriptions;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/webhooks")
public class SubscriptionWebhookController {

    private static final Logger logger = LoggerFactory.getLogger(SubscriptionWebhookController.class);

    private final SubscriptionService subscriptionService;

    @Value("${app.webhook.callback-secret:}")
    private String callbackSecret;

    public SubscriptionWebhookController(SubscriptionService subscriptionService) {
        this.subscriptionService = subscriptionService;
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
        if (subOpt.isEmpty()) {
            logger.warn("No subscription found for stripeSubscriptionId={}", subId);
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
}
