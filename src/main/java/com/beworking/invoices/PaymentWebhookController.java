package com.beworking.invoices;

import java.util.HashMap;
import java.util.Map;
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
public class PaymentWebhookController {

    private static final Logger logger = LoggerFactory.getLogger(PaymentWebhookController.class);

    private final InvoiceService invoiceService;

    @Value("${app.webhook.callback-secret:}")
    private String callbackSecret;

    public PaymentWebhookController(InvoiceService invoiceService) {
        this.invoiceService = invoiceService;
    }

    @PostMapping("/payment-completed")
    public ResponseEntity<Map<String, Object>> paymentCompleted(
        @RequestBody Map<String, String> payload,
        @RequestHeader(value = "X-Callback-Secret", required = false) String secret
    ) {
        if (callbackSecret != null && !callbackSecret.isBlank()) {
            if (secret == null || !secret.equals(callbackSecret)) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
            }
        }

        String reference = payload.get("reference");
        String stripePaymentIntentId = payload.get("stripePaymentIntentId");
        String stripeInvoiceId = payload.get("stripeInvoiceId");

        if ((reference == null || reference.isBlank()) && (stripeInvoiceId == null || stripeInvoiceId.isBlank())) {
            Map<String, Object> error = new HashMap<>();
            error.put("error", "reference or stripeInvoiceId is required");
            return ResponseEntity.badRequest().body(error);
        }

        logger.info("Payment completed webhook — reference={} stripePI={} stripeInvoiceId={}", reference, stripePaymentIntentId, stripeInvoiceId);

        int updated = invoiceService.markInvoicePaid(reference, stripePaymentIntentId, stripeInvoiceId);

        Map<String, Object> response = new HashMap<>();
        response.put("updated", updated);
        response.put("reference", reference);
        return ResponseEntity.ok(response);
    }
}
