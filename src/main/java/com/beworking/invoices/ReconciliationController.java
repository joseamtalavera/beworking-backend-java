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
                account,
                run_date,
                db_active,
                db_stripe,
                db_bank_transfer,
                stripe_active,
                stripe_past_due,
                past_due_amount,
                missing_invoice_count,
                missing_invoices,
                past_due_subs,
                COALESCE(db_only_subs, '[]'::jsonb) as db_only_subs,
                COALESCE(stripe_only_subs, '[]'::jsonb) as stripe_only_subs,
                created_at
            FROM beworking.reconciliation_results
            WHERE run_date = (SELECT MAX(run_date) FROM beworking.reconciliation_results)
            ORDER BY account
            """);
        return ResponseEntity.ok(rows);
    }

    @GetMapping("/breakdown/{account}")
    public ResponseEntity<Map<String, Object>> getBreakdown(@PathVariable String account) {
        String acct = account.toUpperCase();

        List<Map<String, Object>> stripeActive = jdbcTemplate.queryForList("""
            SELECT s.id, s.contact_id, s.stripe_subscription_id, s.stripe_customer_id,
                   s.monthly_amount, s.billing_interval, s.start_date, cp.name, cp.email_primary
            FROM beworking.subscriptions s
            JOIN beworking.contact_profiles cp ON cp.id = s.contact_id
            WHERE s.cuenta = ? AND s.active = true AND s.billing_method = 'stripe'
            AND s.stripe_subscription_id NOT LIKE 'sub_sched_%'
            ORDER BY cp.name
            """, acct);

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

        // Past due from latest reconciliation run
        List<Map<String, Object>> pastDueRow = jdbcTemplate.queryForList("""
            SELECT past_due_subs, stripe_past_due, past_due_amount
            FROM beworking.reconciliation_results
            WHERE account = ? AND run_date = (SELECT MAX(run_date) FROM beworking.reconciliation_results WHERE account = ?)
            """, acct, acct);

        List<?> pastDueSubs = List.of();
        int pastDueCount = 0;
        Object pastDueAmount = 0;
        if (!pastDueRow.isEmpty()) {
            Object raw = pastDueRow.get(0).get("past_due_subs");
            if (raw instanceof List<?> l) pastDueSubs = l;
            else if (raw instanceof String s) {
                try { pastDueSubs = new com.fasterxml.jackson.databind.ObjectMapper().readValue(s, List.class); } catch (Exception ignored) {}
            }
            pastDueCount = ((Number) pastDueRow.get(0).getOrDefault("stripe_past_due", 0)).intValue();
            pastDueAmount = pastDueRow.get(0).getOrDefault("past_due_amount", 0);
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
