package com.beworking.invoices;

import com.beworking.auth.EmailService;
import com.beworking.bookings.Bloqueo;
import com.beworking.bookings.BloqueoRepository;
import com.beworking.contacts.ContactProfile;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class MonthlyInvoiceScheduler {

    private static final Logger logger = LoggerFactory.getLogger(MonthlyInvoiceScheduler.class);

    private static final String ADMIN_EMAIL = "accounts@be-working.com";

    private final BloqueoRepository bloqueoRepository;
    private final InvoiceService invoiceService;
    private final EmailService emailService;
    private final JdbcTemplate jdbcTemplate;
    private final RestClient http;
    private final String paymentsBaseUrl;

    public MonthlyInvoiceScheduler(BloqueoRepository bloqueoRepository,
                                   InvoiceService invoiceService,
                                   EmailService emailService,
                                   JdbcTemplate jdbcTemplate,
                                   @Value("${app.payments.base-url:}") String paymentsBaseUrl) {
        this.bloqueoRepository = bloqueoRepository;
        this.invoiceService = invoiceService;
        this.emailService = emailService;
        this.jdbcTemplate = jdbcTemplate;
        this.http = RestClient.create();
        this.paymentsBaseUrl = paymentsBaseUrl;
    }

    /**
     * Runs on the 28th of each month at 05:00 AM.
     * Invoices all uninvoiced bloqueos for the NEXT month, grouped by contact.
     * Sends a status email to admin on completion or failure.
     */
    @Scheduled(cron = "0 0 5 28 * *")
    public void invoiceCurrentMonth() {
        YearMonth currentMonth = YearMonth.now().plusMonths(1);
        logger.info("Monthly auto-invoicing started for {}", currentMonth);

        int successCount = 0;
        int failCount = 0;
        List<String> errors = new ArrayList<>();

        try {
            int[] result = processMonth(currentMonth);
            successCount = result[0];
            failCount = result[1];
        } catch (Exception e) {
            logger.error("Monthly auto-invoicing failed for {}: {}", currentMonth, e.getMessage(), e);
            errors.add(e.getMessage());
            failCount = -1;
        }

        sendStatusEmail(currentMonth, successCount, failCount, errors);
    }

    public int[] processMonth(YearMonth month) {
        LocalDateTime monthStart = month.atDay(1).atStartOfDay();
        LocalDateTime monthEnd = month.plusMonths(1).atDay(1).atStartOfDay();

        List<Bloqueo> uninvoiced = bloqueoRepository.findUninvoicedForMonth(monthStart, monthEnd);
        if (uninvoiced.isEmpty()) {
            logger.info("No uninvoiced bloqueos found for {}", month);
            return new int[]{0, 0};
        }

        // Group by contact ID
        Map<Long, List<Bloqueo>> byContact = uninvoiced.stream()
            .filter(b -> b.getCliente() != null && b.getCliente().getId() != null)
            .collect(Collectors.groupingBy(b -> b.getCliente().getId()));

        logger.info("Found {} uninvoiced bloqueos across {} contacts for {}",
            uninvoiced.size(), byContact.size(), month);

        int successCount = 0;
        int failCount = 0;

        for (Map.Entry<Long, List<Bloqueo>> entry : byContact.entrySet()) {
            Long contactId = entry.getKey();
            List<Bloqueo> bloqueos = entry.getValue();

            try {
                processContactInvoice(contactId, bloqueos, month);
                successCount++;
            } catch (Exception e) {
                logger.error("Failed to auto-invoice contact {} for {}: {}",
                    contactId, month, e.getMessage(), e);
                failCount++;
            }
        }

        logger.info("Monthly auto-invoicing completed for {}: {} success, {} failed",
            month, successCount, failCount);
        return new int[]{successCount, failCount};
    }

    private void processContactInvoice(Long contactId, List<Bloqueo> bloqueos, YearMonth month) {
        List<Long> bloqueoIds = bloqueos.stream().map(Bloqueo::getId).sorted().toList();

        // 1. Create internal invoice (date = 1st of the invoiced month)
        CreateInvoiceRequest request = new CreateInvoiceRequest();
        request.setBloqueoIds(bloqueoIds);
        request.setVatPercent(BigDecimal.valueOf(21));
        request.setDescription("Factura mensual - " + month.getMonth().name() + " " + month.getYear());
        request.setInvoiceDate(month.atDay(1).atStartOfDay());

        CreateInvoiceResponse response = invoiceService.createInvoice(request);
        logger.info("Internal invoice created: id={}, total={} for contact {}",
            response.id(), response.total(), contactId);

        // 2. Create Stripe invoice
        if (paymentsBaseUrl != null && !paymentsBaseUrl.isBlank()) {
            createStripeInvoice(response, bloqueos.get(0).getCliente(), month);
        } else {
            logger.warn("Stripe payments not configured — skipping Stripe invoice for contact {}", contactId);
        }
    }

    @SuppressWarnings("unchecked")
    private void createStripeInvoice(CreateInvoiceResponse invoice, ContactProfile contact, YearMonth month) {
        // Skip if this internal invoice is already linked to a Stripe invoice
        try {
            Integer linked = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM beworking.facturas WHERE id = ? AND stripeinvoiceid IS NOT NULL AND stripeinvoiceid <> ''",
                Integer.class, invoice.id());
            if (linked != null && linked > 0) {
                logger.info("Internal invoice {} already has a Stripe invoice — skipping", invoice.id());
                return;
            }
        } catch (Exception ignored) {}

        String email = resolveEmail(contact);
        if (email == null || email.isBlank()) {
            logger.warn("No email found for contact {} — skipping Stripe invoice", contact.getId());
            return;
        }

        String customerName = contact.getBillingName() != null ? contact.getBillingName() : contact.getName();
        int amountCents = invoice.total().multiply(BigDecimal.valueOf(100)).intValue();

        Map<String, Object> body = new HashMap<>();
        body.put("customer_email", email);
        body.put("customer_name", customerName);
        body.put("amount", amountCents);
        body.put("currency", "eur");
        body.put("description", invoice.description());
        body.put("due_days", 15);
        body.put("idempotency_key", "monthly-" + contact.getId() + "-" + invoice.id());

        try {
            Map<String, Object> result = http.post()
                .uri(paymentsBaseUrl + "/api/invoices")
                .header("Content-Type", "application/json")
                .body(body)
                .retrieve()
                .body((Class<Map<String, Object>>) (Class<?>) Map.class);

            if (result != null) {
                String stripeInvoiceId = (String) result.get("invoiceId");
                String invoicePdf = (String) result.get("invoicePdf");

                // Link Stripe invoice to internal invoice
                jdbcTemplate.update(
                    "UPDATE beworking.facturas SET stripeinvoiceid = ?, holdedinvoicepdf = ? WHERE id = ?",
                    stripeInvoiceId, invoicePdf, invoice.id()
                );
                logger.info("Stripe invoice {} created and linked to internal invoice {}",
                    stripeInvoiceId, invoice.id());
            }
        } catch (Exception e) {
            logger.error("Failed to create Stripe invoice for internal invoice {}: {}",
                invoice.id(), e.getMessage(), e);
        }
    }

    private String resolveEmail(ContactProfile contact) {
        if (contact.getEmailPrimary() != null && !contact.getEmailPrimary().isBlank()) {
            return contact.getEmailPrimary();
        }
        if (contact.getEmailSecondary() != null && !contact.getEmailSecondary().isBlank()) {
            return contact.getEmailSecondary();
        }
        if (contact.getEmailTertiary() != null && !contact.getEmailTertiary().isBlank()) {
            return contact.getEmailTertiary();
        }
        if (contact.getRepresentativeEmail() != null && !contact.getRepresentativeEmail().isBlank()) {
            return contact.getRepresentativeEmail();
        }
        return null;
    }

    private void sendStatusEmail(YearMonth month, int successCount, int failCount, List<String> errors) {
        boolean allOk = failCount == 0 && errors.isEmpty();
        String statusLabel = allOk ? "OK" : "CON ERRORES";
        String accentColor = allOk ? "#009624" : "#d32f2f";
        String bgColor = allOk ? "#f5faf6" : "#fef5f5";

        String subject = "Facturaci\u00f3n mensual " + month + " \u2014 " + statusLabel;

        StringBuilder html = new StringBuilder();
        html.append("<div style='font-family:Arial,Helvetica,sans-serif;max-width:600px;margin:0 auto;background:#fff;border-radius:12px;overflow:hidden;box-shadow:0 2px 12px rgba(0,0,0,0.08)'>");
        html.append("<div style='background:linear-gradient(135deg,").append(accentColor).append(" 0%,").append(accentColor).append(" 100%);padding:32px;color:#fff'>");
        html.append("<p style='margin:0 0 4px;font-size:13px;letter-spacing:2px;text-transform:uppercase;opacity:0.85'>BEWORKING</p>");
        html.append("<h1 style='margin:0;font-size:22px;font-weight:700'>Facturaci\u00f3n Mensual \u2014 ").append(month).append("</h1>");
        html.append("</div>");
        html.append("<div style='padding:24px 32px'>");

        html.append("<div style='background:").append(bgColor).append(";border-radius:10px;padding:20px 24px;border-left:4px solid ").append(accentColor).append("'>");
        html.append("<table style='border-collapse:collapse;width:100%'>");
        html.append("<tr><td style='padding:6px 12px 6px 0;color:#888;font-size:13px'>Estado</td>");
        html.append("<td style='padding:6px 0;font-size:15px;font-weight:700;color:").append(accentColor).append("'>").append(statusLabel).append("</td></tr>");
        html.append("<tr><td style='padding:6px 12px 6px 0;color:#888;font-size:13px'>Contactos facturados</td>");
        html.append("<td style='padding:6px 0;font-size:15px;color:#333'>").append(successCount).append("</td></tr>");
        html.append("<tr><td style='padding:6px 12px 6px 0;color:#888;font-size:13px'>Errores</td>");
        html.append("<td style='padding:6px 0;font-size:15px;color:#333'>").append(Math.max(failCount, 0)).append("</td></tr>");
        html.append("</table>");
        html.append("</div>");

        if (!errors.isEmpty()) {
            html.append("<div style='margin-top:20px;padding:16px;background:#fef5f5;border-radius:8px'>");
            html.append("<p style='margin:0 0 8px;font-size:14px;font-weight:700;color:#d32f2f'>Detalles del error:</p>");
            for (String err : errors) {
                html.append("<p style='margin:0 0 4px;font-size:13px;color:#666'>").append(err).append("</p>");
            }
            html.append("</div>");
        }

        html.append("</div>");
        html.append("<div style='background:#f9f9f9;padding:12px 32px;text-align:center;border-top:1px solid #eee'>");
        html.append("<p style='margin:0;font-size:12px;color:#aaa'>\u00a9 BeWorking \u00b7 Auto-invoicing system</p>");
        html.append("</div>");
        html.append("</div>");

        try {
            emailService.sendHtml(ADMIN_EMAIL, subject, html.toString());
            logger.info("Status email sent to {} for {}", ADMIN_EMAIL, month);
        } catch (Exception e) {
            logger.error("Failed to send status email for {}: {}", month, e.getMessage(), e);
        }
    }
}
