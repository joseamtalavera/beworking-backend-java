package com.beworking.invoices;

import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
                stripe_active,
                stripe_past_due,
                past_due_amount,
                missing_invoice_count,
                missing_invoices,
                past_due_subs,
                created_at
            FROM beworking.reconciliation_results
            WHERE run_date = (SELECT MAX(run_date) FROM beworking.reconciliation_results)
            ORDER BY account
            """);
        return ResponseEntity.ok(rows);
    }

    @PostMapping("/run")
    public ResponseEntity<Map<String, String>> triggerRun() {
        scheduler.runDailyReconciliation();
        return ResponseEntity.ok(Map.of("status", "ok", "message", "Reconciliation triggered"));
    }
}
