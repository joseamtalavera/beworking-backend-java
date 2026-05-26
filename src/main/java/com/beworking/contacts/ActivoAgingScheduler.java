package com.beworking.contacts;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Daily job that reconciles contact_profiles.status against the funnel rule:
 *
 *   Activo  ⇔  has an active subscription (subscriptions.active = true)
 *              OR has an invoice (facturas.creacionfecha) in the current
 *              calendar month.
 *   Inactivo otherwise (plus Potencial leads, handled by PotencialAgingScheduler).
 *
 * The cron does both directions in one run:
 *   1. Demote Activo → Inactivo when neither condition holds.
 *   2. Promote Inactivo → Activo when either condition starts holding
 *      (e.g. a new factura lands mid-month, or a sub is restored).
 *
 * Two single SQL UPDATEs; idempotent.
 */
@Component
public class ActivoAgingScheduler {

    private static final Logger logger = LoggerFactory.getLogger(ActivoAgingScheduler.class);

    private static final String ACTIVO_CRITERIA = """
        EXISTS (
            SELECT 1
              FROM beworking.subscriptions s
             WHERE s.contact_id = cp.id
               AND s.active = TRUE
        )
        OR EXISTS (
            SELECT 1
              FROM beworking.facturas f
             WHERE f.idcliente = cp.id
               AND f.creacionfecha >= date_trunc('month', NOW())
        )
        """;

    private final JdbcTemplate jdbcTemplate;

    public ActivoAgingScheduler(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    // 04:00 UTC every day, after PotencialAgingScheduler at 03:00.
    @Scheduled(cron = "0 0 4 * * *")
    public void reconcileActivoStatus() {
        runOnce();
    }

    public RunResult runOnce() {
        int demoted = jdbcTemplate.update("""
            UPDATE beworking.contact_profiles cp
               SET status = 'Inactivo',
                   status_changed_at = NOW()
             WHERE cp.status = 'Activo'
               AND NOT (""" + ACTIVO_CRITERIA + ")");

        int promoted = jdbcTemplate.update("""
            UPDATE beworking.contact_profiles cp
               SET status = 'Activo',
                   status_changed_at = NOW()
             WHERE cp.status = 'Inactivo'
               AND (""" + ACTIVO_CRITERIA + ")");

        if (demoted > 0 || promoted > 0) {
            logger.info("Activo reconciliation: promoted={} demoted={} (rule: active sub OR factura this month)",
                promoted, demoted);
        }
        return new RunResult(demoted, promoted);
    }

    public record RunResult(int demoted, int promoted) {}
}
