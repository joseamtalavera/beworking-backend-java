package com.beworking.contacts;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Daily job that ages out dormant Activo contacts.
 *
 * Symmetric counterpart to {@link PotencialAgingScheduler}. The funnel rule is:
 *   Activo means "currently active customer". A contact who hasn't been
 *   invoiced in {@value #DORMANCY_MONTHS} months is treated as dormant and
 *   flipped to Inactivo.
 *
 * What this catches:
 *   - Aula (meeting-room) contacts: one-off bookings, no recurring sub.
 *     They're set to Activo on first paid booking and have no Stripe
 *     subscription, so the customer.subscription.deleted webhook never
 *     touches them. This job is the only path back to Inactivo for them.
 *
 * What this does NOT catch:
 *   - Active OV subscribers — their monthly recurring invoices keep
 *     creacionfecha fresh AND we additionally guard against demoting any
 *     contact with an active subscription row, even if their last invoice
 *     somehow predates the window (Stripe pause, billing drift, etc.).
 *   - Recently-cancelled OV subs — already handled by the
 *     subscription-cancelled webhook.
 *
 * Single SQL UPDATE; idempotent.
 */
@Component
public class ActivoAgingScheduler {

    private static final Logger logger = LoggerFactory.getLogger(ActivoAgingScheduler.class);
    private static final int DORMANCY_MONTHS = 12;

    private final JdbcTemplate jdbcTemplate;

    public ActivoAgingScheduler(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    // 04:00 UTC every day, after PotencialAgingScheduler at 03:00.
    @Scheduled(cron = "0 0 4 * * *")
    public void ageOutDormantActivos() {
        int flipped = jdbcTemplate.update("""
            UPDATE beworking.contact_profiles cp
               SET status = 'Inactivo',
                   status_changed_at = NOW()
             WHERE cp.status = 'Activo'
               AND NOT EXISTS (
                     SELECT 1
                       FROM beworking.facturas f
                      WHERE f.idcliente = cp.id
                        AND f.creacionfecha >= NOW() - (? * INTERVAL '1 month')
                   )
               AND NOT EXISTS (
                     SELECT 1
                       FROM beworking.subscriptions s
                      WHERE s.contact_id = cp.id
                        AND s.active = TRUE
                   )
            """, DORMANCY_MONTHS);

        if (flipped > 0) {
            logger.info("Activo aging cron: flipped {} dormant contacts to Inactivo (no invoice in {} months)",
                flipped, DORMANCY_MONTHS);
        }
    }
}
