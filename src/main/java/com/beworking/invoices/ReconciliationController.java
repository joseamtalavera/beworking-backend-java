package com.beworking.invoices;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/reconciliation")
public class ReconciliationController {

    private final JdbcTemplate jdbcTemplate;
    private final DailyReconciliationScheduler scheduler;

    public ReconciliationController(JdbcTemplate jdbcTemplate,
                                    DailyReconciliationScheduler scheduler) {
        this.jdbcTemplate = jdbcTemplate;
        this.scheduler = scheduler;
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
        return ResponseEntity.ok(rows);
    }

    @GetMapping("/breakdown/{account}")
    public ResponseEntity<Map<String, Object>> getBreakdown(@PathVariable String account) {
        String acct = account.toUpperCase();

        // Load ghost sub IDs from latest reconciliation run FIRST — used to exclude from stripeActive
        List<Map<String, Object>> lastRunRow = jdbcTemplate.queryForList("""
            SELECT past_due_subs, stripe_past_due, past_due_amount, db_only_subs
            FROM beworking.reconciliation_results
            WHERE account = ? AND run_date = (SELECT MAX(run_date) FROM beworking.reconciliation_results WHERE account = ?)
            """, acct, acct);

        List<?> pastDueSubs = List.of();
        int pastDueCount = 0;
        Object pastDueAmount = 0;
        List<String> dbOnlyIds = new java.util.ArrayList<>();
        if (!lastRunRow.isEmpty()) {
            Object raw = lastRunRow.get(0).get("past_due_subs");
            if (raw instanceof List<?> l) pastDueSubs = l;
            else if (raw instanceof String s) {
                try { pastDueSubs = new com.fasterxml.jackson.databind.ObjectMapper().readValue(s, List.class); } catch (Exception ignored) {}
            }
            pastDueCount = ((Number) lastRunRow.get(0).getOrDefault("stripe_past_due", 0)).intValue();
            pastDueAmount = lastRunRow.get(0).getOrDefault("past_due_amount", 0);
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

        // Stripe active = DB stripe-billing rows that are NOT scheduled AND NOT ghost (in db_only_subs).
        // Ghost subs are surfaced in the stripeDeviation bucket instead.
        List<Map<String, Object>> stripeActive;
        if (dbOnlyIds.isEmpty()) {
            stripeActive = jdbcTemplate.queryForList("""
                SELECT s.id, s.contact_id, s.stripe_subscription_id, s.stripe_customer_id,
                       s.monthly_amount, s.billing_interval, s.start_date, cp.name, cp.email_primary
                FROM beworking.subscriptions s
                JOIN beworking.contact_profiles cp ON cp.id = s.contact_id
                WHERE s.cuenta = ? AND s.active = true AND s.billing_method = 'stripe'
                AND s.stripe_subscription_id NOT LIKE 'sub_sched_%'
                ORDER BY cp.name
                """, acct);
        } else {
            String ph = String.join(",", java.util.Collections.nCopies(dbOnlyIds.size(), "?"));
            Object[] args = new Object[dbOnlyIds.size() + 1];
            args[0] = acct;
            for (int i = 0; i < dbOnlyIds.size(); i++) args[i + 1] = dbOnlyIds.get(i);
            stripeActive = jdbcTemplate.queryForList(
                "SELECT s.id, s.contact_id, s.stripe_subscription_id, s.stripe_customer_id, "
                + "s.monthly_amount, s.billing_interval, s.start_date, cp.name, cp.email_primary "
                + "FROM beworking.subscriptions s "
                + "JOIN beworking.contact_profiles cp ON cp.id = s.contact_id "
                + "WHERE s.cuenta = ? AND s.active = true AND s.billing_method = 'stripe' "
                + "AND s.stripe_subscription_id NOT LIKE 'sub_sched_%' "
                + "AND s.stripe_subscription_id NOT IN (" + ph + ") "
                + "ORDER BY cp.name",
                args
            );
        }

        List<Map<String, Object>> stripeScheduled = jdbcTemplate.queryForList("""
            SELECT s.id, s.contact_id, s.stripe_subscription_id, s.stripe_customer_id,
                   s.monthly_amount, s.billing_interval, s.start_date, cp.name, cp.email_primary
            FROM beworking.subscriptions s
            JOIN beworking.contact_profiles cp ON cp.id = s.contact_id
            WHERE s.cuenta = ? AND s.active = true AND s.billing_method = 'stripe'
            AND s.stripe_subscription_id LIKE 'sub_sched_%'
            ORDER BY cp.name
            """, acct);

        List<Map<String, Object>> bankTransfer = jdbcTemplate.queryForList("""
            SELECT s.id, s.contact_id, s.monthly_amount, s.billing_interval,
                   s.last_invoiced_month, s.start_date, cp.name, cp.email_primary
            FROM beworking.subscriptions s
            JOIN beworking.contact_profiles cp ON cp.id = s.contact_id
            WHERE s.cuenta = ? AND s.active = true AND s.billing_method = 'bank_transfer'
            ORDER BY cp.name
            """, acct);

        // Enrich ghost sub IDs with DB contact info
        List<Map<String, Object>> stripeDeviation = new java.util.ArrayList<>();
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
