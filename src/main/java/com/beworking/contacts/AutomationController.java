package com.beworking.contacts;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin-facing endpoints behind the dashboard's Analytics tab.
 *
 *   GET  /api/admin/automation/jobs           — list all crons (description,
 *                                                schedule, last-run summary)
 *   POST /api/admin/automation/jobs/{name}/run — manually trigger one
 *   GET  /api/admin/automation/funnel-stats    — counts + dormancy histogram
 */
@RestController
@RequestMapping("/api/admin/automation")
public class AutomationController {

    private final AbandonmentRecoveryScheduler recoveryScheduler;
    private final PotencialAgingScheduler potencialAging;
    private final ActivoAgingScheduler activoAging;
    private final InactivoReengagementScheduler reengagement;
    private final com.beworking.leads.LeadAgingScheduler leadAging;
    private final com.beworking.leads.LeadNurtureScheduler leadNurture;
    private final com.beworking.invoices.DailyReconciliationScheduler reconciliation;
    private final com.beworking.invoices.MonthlyInvoiceScheduler monthlyInvoice;
    private final com.beworking.subscriptions.LocalSubscriptionScheduler localSubscription;
    private final JdbcTemplate jdbcTemplate;

    public AutomationController(AbandonmentRecoveryScheduler recoveryScheduler,
                                PotencialAgingScheduler potencialAging,
                                ActivoAgingScheduler activoAging,
                                InactivoReengagementScheduler reengagement,
                                com.beworking.leads.LeadAgingScheduler leadAging,
                                com.beworking.leads.LeadNurtureScheduler leadNurture,
                                com.beworking.invoices.DailyReconciliationScheduler reconciliation,
                                com.beworking.invoices.MonthlyInvoiceScheduler monthlyInvoice,
                                com.beworking.subscriptions.LocalSubscriptionScheduler localSubscription,
                                JdbcTemplate jdbcTemplate) {
        this.recoveryScheduler = recoveryScheduler;
        this.potencialAging = potencialAging;
        this.activoAging = activoAging;
        this.reengagement = reengagement;
        this.leadAging = leadAging;
        this.leadNurture = leadNurture;
        this.reconciliation = reconciliation;
        this.monthlyInvoice = monthlyInvoice;
        this.localSubscription = localSubscription;
        this.jdbcTemplate = jdbcTemplate;
    }

    private boolean isAdmin(Authentication authentication) {
        return authentication != null
            && authentication.isAuthenticated()
            && authentication.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
    }

    @GetMapping("/jobs")
    public ResponseEntity<List<Map<String, Object>>> listJobs(Authentication authentication) {
        if (!isAdmin(authentication)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        List<Map<String, Object>> jobs = List.of(
            jobDescriptor(
                "recovery",
                "Recuperación de Potenciales",
                "Envía hasta 4 correos a contactos en estado Potencial (T+30min, T+1d, T+3d, T+6d).",
                "0 0 * * * *",
                "Cada hora",
                countQuery("""
                    SELECT COUNT(*) FROM beworking.contact_profiles
                     WHERE status = 'Potencial'
                       AND created_at >= NOW() - INTERVAL '7 days'
                       AND abandonment_email_count < 4
                """)
            ),
            jobDescriptor(
                "potencialAging",
                "Caducidad de Potenciales",
                "Pasa a Inactivo los contactos en estado Potencial creados hace más de 7 días.",
                "0 0 3 * * *",
                "Diario, 03:00 UTC",
                countQuery("""
                    SELECT COUNT(*) FROM beworking.contact_profiles
                     WHERE status = 'Potencial'
                       AND created_at < NOW() - INTERVAL '7 days'
                """)
            ),
            jobDescriptor(
                "activoAging",
                "Caducidad de Activos",
                "Pasa a Inactivo los contactos Activo sin facturas en los últimos 12 meses (excluye los que tienen suscripción activa).",
                "0 0 4 * * *",
                "Diario, 04:00 UTC",
                countQuery("""
                    SELECT COUNT(*) FROM beworking.contact_profiles cp
                     WHERE cp.status = 'Activo'
                       AND NOT EXISTS (SELECT 1 FROM beworking.facturas f
                                        WHERE f.idcliente = cp.id
                                          AND f.creacionfecha >= NOW() - INTERVAL '12 months')
                       AND NOT EXISTS (SELECT 1 FROM beworking.subscriptions s
                                        WHERE s.contact_id = cp.id AND s.active = TRUE)
                """)
            ),
            jobDescriptor(
                "reengagement",
                "Reactivación de Inactivos",
                "Envía un correo \"hace tiempo que no nos vemos\" a contactos Inactivo cada 6 meses (máx. 3 envíos).",
                "0 0 2 1 * *",
                "Mensual, día 1, 02:00 UTC",
                countQuery("""
                    SELECT COUNT(*) FROM beworking.contact_profiles
                     WHERE status = 'Inactivo'
                       AND reengagement_email_count < 3
                       AND (last_reengagement_email_at IS NULL
                            OR last_reengagement_email_at < NOW() - INTERVAL '6 months')
                """)
            ),
            jobDescriptor(
                "leadNurture",
                "Nurture de Leads (Contactado)",
                "Envía hasta 4 correos a leads en Contactado (T+30min, T+1d, T+3d, T+6d). Misma cadencia que la recuperación de Potenciales.",
                "0 0 * * * *",
                "Cada hora",
                countQuery("""
                    SELECT COUNT(*) FROM beworking.leads
                     WHERE status = 'Contactado'
                       AND status_changed_at >= NOW() - INTERVAL '7 days'
                       AND nurture_email_count < 4
                """)
            ),
            jobDescriptor(
                "leadAging",
                "Caducidad de Leads (Contactado)",
                "Pasa a No-go los leads en estado Contactado durante más de 30 días sin progreso manual. Calificado / Convertido no se tocan.",
                "0 30 2 * * *",
                "Diario, 02:30 UTC",
                countQuery("""
                    SELECT COUNT(*) FROM beworking.leads
                     WHERE status = 'Contactado'
                       AND status_changed_at IS NOT NULL
                       AND status_changed_at < NOW() - INTERVAL '30 days'
                """)
            ),
            jobDescriptor(
                "reconciliation",
                "Reconciliación diaria",
                "Compara subs activas (DB vs Stripe) por cuenta GT y PT. Detecta past-due, ghost subs y facturas pagadas no registradas.",
                "0 0 5 * * *",
                "Diario, 05:00 UTC",
                2L
            ),
            jobDescriptor(
                "monthlyInvoice",
                "Facturación mensual de salas",
                "Crea facturas mensuales para todas las reservas del mes siguiente, agrupadas por contacto.",
                "0 0 5 28 * *",
                "Día 28, 05:00 UTC",
                countQuery("""
                    SELECT COUNT(*) FROM beworking.bloqueos b
                     WHERE b.fecha_ini >= date_trunc('month', NOW() + INTERVAL '1 month')
                       AND b.fecha_ini <  date_trunc('month', NOW() + INTERVAL '2 months')
                       AND b.id_cliente IS NOT NULL
                       AND (b.estado IS NULL OR (
                            LOWER(b.estado) NOT LIKE '%invoice%'
                        AND LOWER(b.estado) NOT LIKE '%factura%'
                        AND LOWER(b.estado) NOT LIKE '%pend%'
                        AND LOWER(b.estado) NOT LIKE '%pag%'
                        AND LOWER(b.estado) NOT LIKE '%grat%'
                        AND LOWER(b.estado) NOT LIKE '%free%'
                       ))
                """)
            ),
            jobDescriptor(
                "localSubscription",
                "Suscripciones por transferencia",
                "Crea facturas Pendiente para suscripciones bank_transfer activas que no se hayan facturado este mes.",
                "0 0 1 1 * *",
                "Día 1, 01:00 UTC",
                countQuery("""
                    SELECT COUNT(*) FROM beworking.subscriptions
                     WHERE active = TRUE
                       AND billing_method = 'bank_transfer'
                       AND (last_invoiced_month IS NULL
                            OR last_invoiced_month <> to_char(NOW(), 'YYYY-MM'))
                """)
            )
        );
        return ResponseEntity.ok(jobs);
    }

    @PostMapping("/jobs/{name}/run")
    public ResponseEntity<Map<String, Object>> runJob(
        @PathVariable String name,
        Authentication authentication
    ) {
        if (!isAdmin(authentication)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        Map<String, Object> result = new HashMap<>();
        result.put("name", name);
        switch (name) {
            case "recovery" -> {
                AbandonmentRecoveryScheduler.RunResult r = recoveryScheduler.runOnce();
                result.put("sent", r.sent());
                result.put("skipped", r.skipped());
                result.put("totalCandidates", r.totalCandidates());
            }
            case "potencialAging" -> {
                PotencialAgingScheduler.RunResult r = potencialAging.runOnce();
                result.put("flipped", r.flipped());
            }
            case "activoAging" -> {
                ActivoAgingScheduler.RunResult r = activoAging.runOnce();
                result.put("flipped", r.flipped());
            }
            case "reengagement" -> {
                InactivoReengagementScheduler.RunResult r = reengagement.runOnce();
                result.put("sent", r.sent());
                result.put("skipped", r.skipped());
                result.put("notDue", r.notDue());
                result.put("totalCandidates", r.totalCandidates());
            }
            case "leadAging" -> {
                com.beworking.leads.LeadAgingScheduler.RunResult r = leadAging.runOnce();
                result.put("flipped", r.flipped());
            }
            case "leadNurture" -> {
                com.beworking.leads.LeadNurtureScheduler.RunResult r = leadNurture.runOnce();
                result.put("sent", r.sent());
                result.put("skipped", r.skipped());
                result.put("totalCandidates", r.totalCandidates());
            }
            case "reconciliation" -> {
                com.beworking.invoices.DailyReconciliationScheduler.RunResult r = reconciliation.runOnce();
                result.put("accountsRun", r.accountsRun());
                result.put("missingInvoices", r.missingInvoices());
                result.put("pastDue", r.pastDue());
                result.put("deviation", r.deviation());
                result.put("issuesFound", r.issuesFound());
            }
            case "monthlyInvoice" -> {
                com.beworking.invoices.MonthlyInvoiceScheduler.RunResult r = monthlyInvoice.runOnce();
                result.put("success", r.success());
                result.put("failed", r.failed());
            }
            case "localSubscription" -> {
                com.beworking.subscriptions.LocalSubscriptionScheduler.RunResult r = localSubscription.runOnce();
                result.put("success", r.success());
                result.put("failed", r.failed());
                result.put("skipped", r.skipped());
                result.put("total", r.total());
            }
            default -> {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                    Map.of("error", "Unknown job: " + name)
                );
            }
        }
        return ResponseEntity.ok(result);
    }

    @GetMapping("/funnel-stats")
    public ResponseEntity<Map<String, Object>> funnelStats(Authentication authentication) {
        if (!isAdmin(authentication)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        Map<String, Long> counts = new LinkedHashMap<>();
        for (String status : List.of("Activo", "Potencial", "Inactivo")) {
            counts.put(status, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM beworking.contact_profiles WHERE status = ?",
                Long.class, status));
        }

        long potencialesConverted30d = jdbcTemplate.queryForObject("""
            SELECT COUNT(*) FROM beworking.contact_profiles cp
             WHERE cp.status = 'Activo'
               AND cp.status_changed_at >= NOW() - INTERVAL '30 days'
               AND EXISTS (SELECT 1 FROM beworking.facturas f
                            WHERE f.idcliente = cp.id
                              AND f.creacionfecha >= cp.status_changed_at - INTERVAL '1 day')
            """, Long.class);

        long potencialesAgedOut30d = jdbcTemplate.queryForObject("""
            SELECT COUNT(*) FROM beworking.contact_profiles
             WHERE status = 'Inactivo'
               AND status_changed_at >= NOW() - INTERVAL '30 days'
               AND created_at < NOW() - INTERVAL '7 days'
               AND created_at > NOW() - INTERVAL '60 days'
            """, Long.class);

        Map<String, Long> recoveryByTemplate = new LinkedHashMap<>();
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
            SELECT abandonment_email_count AS n, COUNT(*) AS c
              FROM beworking.contact_profiles
             WHERE last_recovery_email_at >= NOW() - INTERVAL '30 days'
             GROUP BY abandonment_email_count
             ORDER BY abandonment_email_count
            """);
        for (Map<String, Object> row : rows) {
            recoveryByTemplate.put(String.valueOf(row.get("n")), ((Number) row.get("c")).longValue());
        }

        // Open tracking: distinct contact-opens per (type, template_number)
        // in the last 30 days. Multiple opens by same recipient count as 1 —
        // matches industry "open rate" convention.
        Map<String, Long> recoveryOpensByTemplate = new LinkedHashMap<>();
        List<Map<String, Object>> opensRows = jdbcTemplate.queryForList("""
            SELECT template_number AS n, COUNT(DISTINCT contact_id) AS c
              FROM beworking.email_opens
             WHERE template_type = 'recovery'
               AND opened_at >= NOW() - INTERVAL '30 days'
             GROUP BY template_number
             ORDER BY template_number
            """);
        for (Map<String, Object> row : opensRows) {
            recoveryOpensByTemplate.put(String.valueOf(row.get("n")), ((Number) row.get("c")).longValue());
        }

        long reengagementOpens30d = jdbcTemplate.queryForObject("""
            SELECT COUNT(DISTINCT contact_id) FROM beworking.email_opens
             WHERE template_type = 'reengagement'
               AND opened_at >= NOW() - INTERVAL '30 days'
            """, Long.class);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("counts", counts);
        response.put("potencialesConverted30d", potencialesConverted30d);
        response.put("potencialesAgedOut30d", potencialesAgedOut30d);
        response.put("recoveryEmailsByTemplate30d", recoveryByTemplate);
        response.put("recoveryOpensByTemplate30d", recoveryOpensByTemplate);
        response.put("reengagementOpens30d", reengagementOpens30d);
        return ResponseEntity.ok(response);
    }

    private Map<String, Object> jobDescriptor(String name, String label, String description,
                                              String cron, String cadence, long candidates) {
        Map<String, Object> job = new LinkedHashMap<>();
        job.put("name", name);
        job.put("domain", domainFor(name));
        job.put("label", label);
        job.put("description", description);
        job.put("cron", cron);
        job.put("cadence", cadence);
        job.put("candidates", candidates);
        return job;
    }

    private long countQuery(String sql) {
        Long n = jdbcTemplate.queryForObject(sql, Long.class);
        return n == null ? 0L : n;
    }

    private static String domainFor(String name) {
        return switch (name) {
            case "recovery", "potencialAging", "activoAging", "reengagement", "leadAging", "leadNurture" -> "contacts";
            case "reconciliation", "monthlyInvoice", "localSubscription" -> "billing";
            default -> "other";
        };
    }
}
