package com.beworking.invoices;

import com.beworking.auth.EmailService;
import java.util.HashMap;
import java.util.List;
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
    private final EmailService emailService;

    @Value("${app.webhook.callback-secret:}")
    private String callbackSecret;

    @Value("${app.invoice.cc-email:info@be-working.com}")
    private String invoiceCcEmail;

    public PaymentWebhookController(InvoiceService invoiceService, EmailService emailService) {
        this.invoiceService = invoiceService;
        this.emailService = emailService;
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

    @SuppressWarnings("unchecked")
    @PostMapping("/invoice-sent")
    public ResponseEntity<Map<String, Object>> invoiceSent(
        @RequestBody Map<String, Object> payload,
        @RequestHeader(value = "X-Callback-Secret", required = false) String secret
    ) {
        if (callbackSecret != null && !callbackSecret.isBlank()) {
            if (secret == null || !secret.equals(callbackSecret)) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
            }
        }

        String stripeInvoiceId = (String) payload.getOrDefault("stripeInvoiceId", "");
        String customerEmail = (String) payload.getOrDefault("customerEmail", "");
        String customerName = (String) payload.getOrDefault("customerName", "");
        Number amountCents = (Number) payload.getOrDefault("amountDueCents", 0);
        String currency = (String) payload.getOrDefault("currency", "eur");
        String hostedUrl = (String) payload.getOrDefault("hostedInvoiceUrl", "");
        String invoicePdf = (String) payload.getOrDefault("invoicePdf", "");
        List<String> lineDescriptions = (List<String>) payload.getOrDefault("lineDescriptions", List.of());

        double amount = amountCents.doubleValue() / 100.0;
        String formattedAmount = String.format("%.2f %s", amount, currency.toUpperCase());
        String linesHtml = lineDescriptions.isEmpty()
            ? ""
            : "<ul>" + String.join("", lineDescriptions.stream().map(d -> "<li>" + d + "</li>").toList()) + "</ul>";

        String subject = "Factura enviada a " + (customerName.isBlank() ? customerEmail : customerName)
            + " — " + formattedAmount;
        String html = "<p>Se ha enviado una factura de Stripe al cliente:</p>"
            + "<table style='border-collapse:collapse;'>"
            + "<tr><td style='padding:4px 12px 4px 0;font-weight:bold;'>Cliente:</td><td>" + customerName + " (" + customerEmail + ")</td></tr>"
            + "<tr><td style='padding:4px 12px 4px 0;font-weight:bold;'>Importe:</td><td>" + formattedAmount + "</td></tr>"
            + "<tr><td style='padding:4px 12px 4px 0;font-weight:bold;'>ID Factura:</td><td>" + stripeInvoiceId + "</td></tr>"
            + "</table>"
            + (linesHtml.isEmpty() ? "" : "<p><strong>Conceptos:</strong></p>" + linesHtml)
            + (hostedUrl.isBlank() ? "" : "<p><a href='" + hostedUrl + "'>Ver factura en Stripe</a></p>")
            + (invoicePdf.isBlank() ? "" : "<p><a href='" + invoicePdf + "'>Descargar PDF</a></p>");

        logger.info("Invoice sent webhook — sending CC to {} for invoice {} (customer={})",
            invoiceCcEmail, stripeInvoiceId, customerEmail);

        emailService.sendHtml(invoiceCcEmail, subject, html);

        Map<String, Object> response = new HashMap<>();
        response.put("ccSentTo", invoiceCcEmail);
        response.put("stripeInvoiceId", stripeInvoiceId);
        return ResponseEntity.ok(response);
    }
}
