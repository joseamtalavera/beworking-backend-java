package com.beworking.reports;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
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
    private final RestClient http = buildHttpClient();
    private final String paymentsBaseUrl;

    /** Bounded HTTP client: a single slow stripe-service call can't hang the scan. */
    private static RestClient buildHttpClient() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(3));
        factory.setReadTimeout(Duration.ofSeconds(6));
        return RestClient.builder().requestFactory(factory).build();
    }

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

        // Each row needs one blocking call to stripe-service, so fan them out
        // across a bounded pool — 189 serial round-trips overran the ALB's 504
        // gateway timeout. Pool is sized to the work but capped to avoid
        // hammering stripe-service.
        int poolSize = Math.min(16, Math.max(1, rows.size()));
        ExecutorService pool = Executors.newFixedThreadPool(poolSize);
        List<Discrepancy> out = new ArrayList<>();
        try {
            List<Future<Discrepancy>> futures = new ArrayList<>(rows.size());
            for (Map<String, Object> r : rows) {
                futures.add(pool.submit(() -> evaluateRow(r)));
            }
            for (Future<Discrepancy> f : futures) {
                try {
                    Discrepancy d = f.get();
                    if (d != null) out.add(d);
                } catch (Exception e) {
                    logger.warn("Price discrepancy row failed: {}", e.getMessage());
                }
            }
        } finally {
            pool.shutdown();
        }

        logger.info("Price discrepancy check: scanned {} paid invoices, found {} mismatches",
            rows.size(), out.size());
        return out;
    }

    /** Compare one DB row's total against Stripe's collected amount. Null = no discrepancy. */
    private Discrepancy evaluateRow(Map<String, Object> r) {
        String stripeId = (String) r.get("stripeinvoiceid");
        String cuenta = (String) r.get("cuenta");
        BigDecimal dbAmount = r.get("total") != null
            ? new BigDecimal(r.get("total").toString()).setScale(2, RoundingMode.HALF_UP)
            : BigDecimal.ZERO;

        Map<String, Object> stripeInvoice = fetchStripeInvoice(stripeId, cuenta);
        if (stripeInvoice == null) return null;

        // A price discrepancy only exists when Stripe actually COLLECTED a
        // different amount than the DB recorded — i.e. status 'paid'. Every
        // other status legitimately has amount_paid = 0 and must be skipped,
        // not flagged:
        //   - void / uncollectible / draft → cancelled, never collected
        //   - open / processing            → SEPA Direct Debit still pending
        //     settlement. charge_automatically SEPA invoices sit 'open' with
        //     amount_paid 0 for days (we mark the DB row 'pagado' optimistically)
        //     then flip to 'paid' once the debit clears. Comparing during that
        //     window produces a phantom full-amount discrepancy.
        String stripeStatus = stripeInvoice.get("status") != null
            ? stripeInvoice.get("status").toString().toLowerCase()
            : "";
        if (!stripeStatus.equals("paid")) {
            return null;
        }

        Object paid = stripeInvoice.get("amountPaid");
        if (paid == null) paid = stripeInvoice.get("amountDue"); // fallback if stripe-service is older
        if (paid == null) return null;
        BigDecimal stripeAmount = new BigDecimal(paid.toString()).setScale(2, RoundingMode.HALF_UP);

        BigDecimal delta = stripeAmount.subtract(dbAmount).abs();
        if (delta.compareTo(THRESHOLD_EUR) <= 0) {
            return null;
        }
        return new Discrepancy(
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
        );
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
