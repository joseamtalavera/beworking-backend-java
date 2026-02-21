package com.beworking.invoices;

import com.beworking.bookings.Bloqueo;
import com.beworking.bookings.BloqueoRepository;
import com.beworking.contacts.ContactProfile;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.YearMonth;
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

    private final BloqueoRepository bloqueoRepository;
    private final InvoiceService invoiceService;
    private final JdbcTemplate jdbcTemplate;
    private final RestClient http;
    private final String paymentsBaseUrl;

    public MonthlyInvoiceScheduler(BloqueoRepository bloqueoRepository,
                                   InvoiceService invoiceService,
                                   JdbcTemplate jdbcTemplate,
                                   @Value("${app.payments.base-url:}") String paymentsBaseUrl) {
        this.bloqueoRepository = bloqueoRepository;
        this.invoiceService = invoiceService;
        this.jdbcTemplate = jdbcTemplate;
        this.http = RestClient.create();
        this.paymentsBaseUrl = paymentsBaseUrl;
    }

    /**
     * Runs on the 1st of each month at 08:00 AM.
     * Invoices all uninvoiced bloqueos for the current month, grouped by contact.
     */
    @Scheduled(cron = "0 0 8 1 * *")
    public void invoiceCurrentMonth() {
        YearMonth currentMonth = YearMonth.now();
        logger.info("Monthly auto-invoicing started for {}", currentMonth);
        processMonth(currentMonth);
    }

    public void processMonth(YearMonth month) {
        LocalDateTime monthStart = month.atDay(1).atStartOfDay();
        LocalDateTime monthEnd = month.plusMonths(1).atDay(1).atStartOfDay();

        List<Bloqueo> uninvoiced = bloqueoRepository.findUninvoicedForMonth(monthStart, monthEnd);
        if (uninvoiced.isEmpty()) {
            logger.info("No uninvoiced bloqueos found for {}", month);
            return;
        }

        // Group by contact ID
        Map<Long, List<Bloqueo>> byContact = uninvoiced.stream()
            .filter(b -> b.getCliente() != null && b.getCliente().getId() != null)
            .collect(Collectors.groupingBy(b -> b.getCliente().getId()));

        logger.info("Found {} uninvoiced bloqueos across {} contacts for {}",
            uninvoiced.size(), byContact.size(), month);

        for (Map.Entry<Long, List<Bloqueo>> entry : byContact.entrySet()) {
            Long contactId = entry.getKey();
            List<Bloqueo> bloqueos = entry.getValue();

            try {
                processContactInvoice(contactId, bloqueos, month);
            } catch (Exception e) {
                logger.error("Failed to auto-invoice contact {} for {}: {}",
                    contactId, month, e.getMessage(), e);
            }
        }

        logger.info("Monthly auto-invoicing completed for {}", month);
    }

    private void processContactInvoice(Long contactId, List<Bloqueo> bloqueos, YearMonth month) {
        List<Long> bloqueoIds = bloqueos.stream().map(Bloqueo::getId).toList();

        // 1. Create internal invoice
        CreateInvoiceRequest request = new CreateInvoiceRequest();
        request.setBloqueoIds(bloqueoIds);
        request.setVatPercent(BigDecimal.valueOf(21));
        request.setDescription("Factura mensual - " + month.getMonth().name() + " " + month.getYear());

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
}
