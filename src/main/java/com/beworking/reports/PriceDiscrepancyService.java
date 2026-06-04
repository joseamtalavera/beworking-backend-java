package com.beworking.reports;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/**
 * Compares the locally-stored factura total against Stripe's authoritative
 * {@code amount_paid} for every paid invoice in the last 30 days. Anything
 * diverging by more than {@link #THRESHOLD_EUR} is surfaced as a discrepancy.
 *
 * Stripe is the source of truth — the report tells you "DB is wrong, fix it".
 * No auto-correction; admin decides whether to update the DB row, void/reissue,
 * or chase the customer.
 *
 * Used by the dashboard audit card (Price Discrepancies tab) and the daily
 * PriceDiscrepancyAuditScheduler email to info@.
 */
@Service
public class PriceDiscrepancyService {

    private static final Logger logger = LoggerFactory.getLogger(PriceDiscrepancyService.class);
    private static final BigDecimal THRESHOLD_EUR = new BigDecimal("0.01");

    private final JdbcTemplate jdbcTemplate;
    private final RestClient http = RestClient.create();
    private final String paymentsBaseUrl;

    public PriceDiscrepancyService(JdbcTemplate jdbcTemplate,
                                   @Value("${app.payments.base-url:}") String paymentsBaseUrl) {
        this.jdbcTemplate = jdbcTemplate;
        this.paymentsBaseUrl = paymentsBaseUrl;
    }

    public List<Discrepancy> findRecent() {
        if (paymentsBaseUrl == null || paymentsBaseUrl.isBlank()) {
            logger.warn("paymentsBaseUrl not configured — skipping price discrepancy check");
            return List.of();
        }

        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
            SELECT f.id, f.idfactura, f.total, f.creacionfecha, f.fechacobro1,
                   f.stripeinvoiceid,
                   UPPER(COALESCE(NULLIF(f.holdedcuenta, ''), 'PT')) AS cuenta,
                   cp.name, cp.email_primary
              FROM beworking.facturas f
              JOIN beworking.contact_profiles cp ON cp.id = f.idcliente
             WHERE f.stripeinvoiceid IS NOT NULL AND f.stripeinvoiceid <> ''
               AND LOWER(COALESCE(f.estado, '')) = 'pagado'
               AND f.creacionfecha >= NOW() - INTERVAL '30 days'
               AND f.idfactura < 100000
             ORDER BY f.creacionfecha DESC
            """);

        List<Discrepancy> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            String stripeId = (String) r.get("stripeinvoiceid");
            String cuenta = (String) r.get("cuenta");
            BigDecimal dbAmount = r.get("total") != null
                ? new BigDecimal(r.get("total").toString()).setScale(2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

            Map<String, Object> stripeInvoice = fetchStripeInvoice(stripeId, cuenta);
            if (stripeInvoice == null) continue;

            // Void / uncollectible / draft invoices legitimately have
            // amount_paid = 0 — they're not price discrepancies, they're
            // cancelled. Skip them so they don't pollute the audit.
            String stripeStatus = stripeInvoice.get("status") != null
                ? stripeInvoice.get("status").toString().toLowerCase()
                : "";
            if (stripeStatus.equals("void") || stripeStatus.equals("uncollectible")
                || stripeStatus.equals("draft")) {
                continue;
            }

            Object paid = stripeInvoice.get("amountPaid");
            if (paid == null) paid = stripeInvoice.get("amountDue"); // fallback if stripe-service is older
            if (paid == null) continue;
            BigDecimal stripeAmount = new BigDecimal(paid.toString()).setScale(2, RoundingMode.HALF_UP);

            BigDecimal delta = stripeAmount.subtract(dbAmount).abs();
            if (delta.compareTo(THRESHOLD_EUR) > 0) {
                out.add(new Discrepancy(
                    ((Number) r.get("id")).longValue(),
                    r.get("idfactura") != null ? ((Number) r.get("idfactura")).intValue() : null,
                    cuenta,
                    stripeId,
                    (String) r.get("name"),
                    (String) r.get("email_primary"),
                    dbAmount,
                    stripeAmount,
                    delta,
                    r.get("creacionfecha") instanceof java.sql.Timestamp ts ? ts.toLocalDateTime() : null
                ));
            }
        }

        if (!out.isEmpty()) {
            logger.info("Price discrepancy check: scanned {} paid invoices, found {} mismatches",
                rows.size(), out.size());
        }
        return out;
    }

    private Map<String, Object> fetchStripeInvoice(String stripeInvoiceId, String account) {
        try {
            return http.get()
                .uri(paymentsBaseUrl + "/api/invoices/" + stripeInvoiceId + "/hosted-url?account=" + account)
                .retrieve()
                .body(new ParameterizedTypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            logger.warn("Failed to fetch Stripe invoice for {}: {}", stripeInvoiceId, e.getMessage());
            return null;
        }
    }

    public record Discrepancy(
        Long facturaId,
        Integer idfactura,
        String cuenta,
        String stripeInvoiceId,
        String customerName,
        String customerEmail,
        BigDecimal dbAmount,
        BigDecimal stripeAmount,
        BigDecimal delta,
        LocalDateTime creacionfecha
    ) {}
}
