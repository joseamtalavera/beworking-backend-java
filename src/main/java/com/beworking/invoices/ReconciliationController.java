package com.beworking.invoices;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClient;

@RestController
@RequestMapping("/api/admin/reconciliation")
public class ReconciliationController {

    private static final Logger logger = LoggerFactory.getLogger(ReconciliationController.class);

    private final JdbcTemplate jdbcTemplate;
    private final DailyReconciliationScheduler scheduler;
    private final RestClient http = RestClient.create();
    private final String paymentsBaseUrl;

    public ReconciliationController(JdbcTemplate jdbcTemplate,
                                    DailyReconciliationScheduler scheduler,
                                    @Value("${app.payments.base-url:}") String paymentsBaseUrl) {
        this.jdbcTemplate = jdbcTemplate;
        this.scheduler = scheduler;
        this.paymentsBaseUrl = paymentsBaseUrl;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> fetchStripeDetail(String account) {
        if (paymentsBaseUrl == null || paymentsBaseUrl.isBlank()) {
            return Map.of();
        }
        try {
            return http.get()
                .uri(paymentsBaseUrl + "/api/reconciliation/" + account + "/detail")
                .retrieve()
                .body(Map.class);
        } catch (Exception e) {
            logger.warn("Failed to fetch Stripe detail for {}: {}", account, e.getMessage());
            return Map.of();
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> fetchStripeCounts(String account) {
        if (paymentsBaseUrl == null || paymentsBaseUrl.isBlank()) return null;
        try {
            return http.get()
                .uri(paymentsBaseUrl + "/api/reconciliation/" + account + "/counts")
                .retrieve()
                .body(Map.class);
        } catch (Exception e) {
            logger.warn("Failed to fetch Stripe counts for {}: {}", account, e.getMessage());
            return null;
        }
    }

    @GetMapping("/latest")
    public ResponseEntity<List<Map<String, Object>>> getLatest() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
            SELECT
                r.account,
                r.run_date,
                r.db_active,
                r.db_stripe,
                r.db_bank_transfer,
                r.stripe_active,
                r.stripe_past_due,
                r.past_due_amount,
                r.missing_invoice_count,
                r.missing_invoices,
                r.past_due_subs,
                COALESCE(r.db_only_subs, '[]'::jsonb) as db_only_subs,
                COALESCE(r.stripe_only_subs, '[]'::jsonb) as stripe_only_subs,
                r.created_at,
                COALESCE((
                    SELECT COUNT(*) FROM beworking.subscriptions s
                    WHERE s.cuenta = r.account AND s.active = true
                      AND s.billing_method = 'stripe'
                      AND s.stripe_subscription_id LIKE 'sub_sched_%'
                ), 0) AS db_scheduled
            FROM beworking.reconciliation_results r
            WHERE r.run_date = (SELECT MAX(run_date) FROM beworking.reconciliation_results)
            ORDER BY r.account
            """);

        // Overwrite Stripe-sourced fields with live counts from stripe-service so card KPIs
        // match live reality (card + popup always agree).
        for (Map<String, Object> row : rows) {
            String acct = String.valueOf(row.get("account"));
            Map<String, Object> live = fetchStripeCounts(acct);
            if (live == null) continue;
            Object act = live.get("active");
            Object pd = live.get("pastDue");
            Object pda = live.get("pastDueAmount");
            Object sch = live.get("scheduled");
            if (act != null) row.put("stripe_active", act);
            if (pd != null) row.put("stripe_past_due", pd);
            if (pda != null) row.put("past_due_amount", pda);
            if (sch != null) row.put("db_scheduled", sch);
        }

        return ResponseEntity.ok(rows);
    }

    @GetMapping("/breakdown/{account}")
    @SuppressWarnings("unchecked")
    public ResponseEntity<Map<String, Object>> getBreakdown(@PathVariable String account) {
        String acct = account.toUpperCase();

        // STRIPE, SCHEDULED, OVERDUE → authoritative, fetched live from Stripe
        Map<String, Object> stripeDetail = fetchStripeDetail(acct);
        List<Map<String, Object>> stripeActiveOnly = (List<Map<String, Object>>) stripeDetail.getOrDefault("active", List.of());
        List<Map<String, Object>> stripeScheduled = (List<Map<String, Object>>) stripeDetail.getOrDefault("scheduled", List.of());
        List<Map<String, Object>> pastDueSubs = (List<Map<String, Object>>) stripeDetail.getOrDefault("pastDue", List.of());

        // Stripe KPI/popup = all live subs (active + past_due). past_due is still a live subscription.
        List<Map<String, Object>> stripeActive = new ArrayList<>(stripeActiveOnly);
        stripeActive.addAll(pastDueSubs);
        stripeActive.sort((a, b) -> {
            String na = String.valueOf(a.getOrDefault("name", "")).toLowerCase();
            String nb = String.valueOf(b.getOrDefault("name", "")).toLowerCase();
            return na.compareTo(nb);
        });

        int pastDueCount = pastDueSubs.size();
        double pastDueAmount = pastDueSubs.stream()
            .mapToDouble(r -> ((Number) r.getOrDefault("amountDue", 0)).doubleValue())
            .sum();

        // BANK TRANSFER → DB (no Stripe equivalent)
        List<Map<String, Object>> bankTransfer = jdbcTemplate.queryForList("""
            SELECT s.id, s.contact_id, s.monthly_amount, s.billing_interval,
                   s.last_invoiced_month, s.start_date, cp.name, cp.email_primary
            FROM beworking.subscriptions s
            JOIN beworking.contact_profiles cp ON cp.id = s.contact_id
            WHERE s.cuenta = ? AND s.active = true AND s.billing_method = 'bank_transfer'
            ORDER BY cp.name
            """, acct);

        // DEVIATION → DB ghosts from last reconciliation run (by definition a DB-vs-Stripe diff)
        List<String> dbOnlyIds = new ArrayList<>();
        List<Map<String, Object>> lastRunRow = jdbcTemplate.queryForList("""
            SELECT db_only_subs
            FROM beworking.reconciliation_results
            WHERE account = ? AND run_date = (SELECT MAX(run_date) FROM beworking.reconciliation_results WHERE account = ?)
            """, acct, acct);
        if (!lastRunRow.isEmpty()) {
            Object rawDbOnly = lastRunRow.get(0).get("db_only_subs");
            if (rawDbOnly instanceof List<?> l) {
                for (Object o : l) if (o instanceof String s) dbOnlyIds.add(s);
            } else if (rawDbOnly instanceof String s) {
                try {
                    List<?> parsed = new com.fasterxml.jackson.databind.ObjectMapper().readValue(s, List.class);
                    for (Object o : parsed) if (o instanceof String str) dbOnlyIds.add(str);
                } catch (Exception ignored) {}
            }
        }

        List<Map<String, Object>> stripeDeviation = new ArrayList<>();
        if (!dbOnlyIds.isEmpty()) {
            String placeholders = String.join(",", java.util.Collections.nCopies(dbOnlyIds.size(), "?"));
            Object[] qArgs = dbOnlyIds.toArray();
            stripeDeviation = jdbcTemplate.queryForList(
                "SELECT s.id, s.contact_id, s.stripe_subscription_id, s.stripe_customer_id, "
                + "s.monthly_amount, s.billing_interval, s.start_date, cp.name, cp.email_primary "
                + "FROM beworking.subscriptions s "
                + "JOIN beworking.contact_profiles cp ON cp.id = s.contact_id "
                + "WHERE s.stripe_subscription_id IN (" + placeholders + ") "
                + "ORDER BY cp.name",
                qArgs
            );
        }

        Map<String, Object> result = new HashMap<>();
        result.put("account", acct);
        result.put("stripeActive", stripeActive);
        result.put("stripeActiveCount", stripeActive.size());
        result.put("stripeScheduled", stripeScheduled);
        result.put("stripeScheduledCount", stripeScheduled.size());
        result.put("bankTransfer", bankTransfer);
        result.put("bankTransferCount", bankTransfer.size());
        result.put("pastDueSubs", pastDueSubs);
        result.put("pastDueCount", pastDueCount);
        result.put("pastDueAmount", pastDueAmount);
        result.put("stripeDeviation", stripeDeviation);
        result.put("stripeDeviationCount", stripeDeviation.size());
        result.put("totalActive", stripeActive.size() + stripeScheduled.size() + bankTransfer.size());

        return ResponseEntity.ok(result);
    }

    @PostMapping("/run")
    public ResponseEntity<Map<String, Object>> triggerRun() {
        long t0 = System.currentTimeMillis();
        try {
            scheduler.runDailyReconciliation();
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of(
                "status", "error",
                "message", "Reconciliation failed: " + e.getClass().getSimpleName() + ": " + (e.getMessage() != null ? e.getMessage() : ""),
                "durationMs", System.currentTimeMillis() - t0
            ));
        }
        // Count rows persisted today to verify the run actually saved
        Integer rowsToday = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM beworking.reconciliation_results WHERE run_date = CURRENT_DATE",
            Integer.class);
        return ResponseEntity.ok(Map.of(
            "status", "ok",
            "message", "Reconciliation triggered",
            "rowsToday", rowsToday != null ? rowsToday : 0,
            "durationMs", System.currentTimeMillis() - t0
        ));
    }
}
