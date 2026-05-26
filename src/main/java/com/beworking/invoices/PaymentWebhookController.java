package com.beworking.invoices;

import com.beworking.auth.EmailService;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
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
        String dash = "—";
        String displayName = customerName.isBlank() ? customerEmail : customerName;

        String linesHtml = lineDescriptions.isEmpty()
            ? ""
            : "<div style=\"padding:8px 0 0;\">"
              + "<div style=\"font-family:Arial,Helvetica,sans-serif;font-size:12px;color:#667085;text-transform:uppercase;letter-spacing:.3px;margin-bottom:6px;\">Conceptos</div>"
              + "<ul style=\"margin:0;padding-left:18px;font-family:Arial,Helvetica,sans-serif;font-size:14px;color:#222;\">"
              + String.join("", lineDescriptions.stream()
                    .map(d -> "<li style=\"margin:2px 0;\">" + d + "</li>")
                    .toList())
              + "</ul></div>";

        String waText = "Hola" + (customerName.isBlank() ? "" : " " + customerName)
            + ", aquí tienes tu factura de BeWorking por " + formattedAmount
            + (hostedUrl.isBlank() ? "" : ": " + hostedUrl);
        String waUrl = "https://wa.me/?text=" + URLEncoder.encode(waText, StandardCharsets.UTF_8);

        StringBuilder buttons = new StringBuilder("<div style=\"margin-top:20px;text-align:center;\">");
        if (!hostedUrl.isBlank()) {
            buttons.append("<a href=\"").append(hostedUrl)
                   .append("\" style=\"display:inline-block;margin:4px 6px;background:#009624;color:#ffffff;")
                   .append("text-decoration:none;font-family:Arial,Helvetica,sans-serif;font-weight:700;font-size:14px;")
                   .append("padding:12px 20px;border-radius:8px;\">Ver factura en Stripe</a>");
        }
        if (!invoicePdf.isBlank()) {
            buttons.append("<a href=\"").append(invoicePdf)
                   .append("\" style=\"display:inline-block;margin:4px 6px;background:#ffffff;color:#009624;")
                   .append("text-decoration:none;font-family:Arial,Helvetica,sans-serif;font-weight:700;font-size:14px;")
                   .append("padding:12px 20px;border-radius:8px;border:1px solid #009624;\">Descargar PDF</a>");
        }
        if (!hostedUrl.isBlank()) {
            buttons.append("<a href=\"").append(waUrl)
                   .append("\" style=\"display:inline-block;margin:4px 6px;background:#25D366;color:#ffffff;")
                   .append("text-decoration:none;font-family:Arial,Helvetica,sans-serif;font-weight:700;font-size:14px;")
                   .append("padding:12px 20px;border-radius:8px;\">Enviar por WhatsApp</a>");
        }
        buttons.append("</div>");

        String html = "<!doctype html><html lang=\"es\"><head><meta charset=\"utf-8\">"
            + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
            + "<title>Factura enviada</title></head>"
            + "<body style=\"margin:0;padding:0;background:#f7f7f8;-webkit-font-smoothing:antialiased;\">"
            + "<table role=\"presentation\" width=\"100%\" cellspacing=\"0\" cellpadding=\"0\" border=\"0\">"
            + "<tr><td align=\"center\" style=\"padding:24px 0;\">"
            + "<table role=\"presentation\" width=\"600\" cellspacing=\"0\" cellpadding=\"0\" border=\"0\" style=\"width:600px;max-width:600px;margin:0 auto;\">"
            + "<tr><td style=\"background:linear-gradient(135deg,#009624 0%,#00c853 100%);padding:28px 32px;color:#ffffff;border-radius:14px 14px 0 0;\">"
            + "<p style=\"margin:0 0 4px;font-family:Arial,Helvetica,sans-serif;font-size:13px;letter-spacing:2px;text-transform:uppercase;opacity:0.85;\">BEWORKING</p>"
            + "<h1 style=\"margin:0;font-family:Arial,Helvetica,sans-serif;font-size:22px;font-weight:700;line-height:1.2;color:#ffffff;\">Factura enviada</h1>"
            + "</td></tr>"
            + "<tr><td style=\"background:#ffffff;padding:28px 32px;border-radius:0 0 14px 14px;border:1px solid #eee;border-top:0;\">"
            + "<p style=\"margin:0 0 18px;font-family:Arial,Helvetica,sans-serif;font-size:14px;color:#555;\">Se ha enviado una factura de Stripe al cliente.</p>"
            + row("Cliente", displayName)
            + row("Email", customerEmail.isBlank() ? dash : customerEmail)
            + row("Importe", formattedAmount)
            + row("ID Stripe", stripeInvoiceId.isBlank() ? dash : stripeInvoiceId)
            + linesHtml
            + buttons.toString()
            + "</td></tr></table></td></tr></table></body></html>";

        String subject = "🧾 Factura enviada — " + displayName + " — " + formattedAmount;

        logger.info("Invoice sent webhook — sending CC to {} for invoice {} (customer={})",
            invoiceCcEmail, stripeInvoiceId, customerEmail);

        emailService.sendHtml(invoiceCcEmail, subject, html);

        Map<String, Object> response = new HashMap<>();
        response.put("ccSentTo", invoiceCcEmail);
        response.put("stripeInvoiceId", stripeInvoiceId);
        return ResponseEntity.ok(response);
    }

    private static String row(String label, String value) {
        return "<div style=\"padding:8px 0;border-bottom:1px dashed #eee;\">"
            + "<div style=\"font-family:Arial,Helvetica,sans-serif;font-size:12px;color:#667085;text-transform:uppercase;letter-spacing:.3px;\">" + label + "</div>"
            + "<div style=\"font-family:Arial,Helvetica,sans-serif;font-size:16px;font-weight:700;color:#111;word-break:break-all;\">" + value + "</div></div>";
    }
}
