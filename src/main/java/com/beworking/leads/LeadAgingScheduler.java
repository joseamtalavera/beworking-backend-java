package com.beworking.leads;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Daily job that ages out leads stuck in the 'Contactado' state.
 *
 * Funnel rule: once a lead reaches 'Contactado' (set automatically by
 * LeadEmailListener after the acknowledgment email lands), the lifecycle is:
 *   - Days 0–7  → LeadNurtureScheduler sends 4 follow-up emails
 *   - Days 7–30 → grace period, no automated touches
 *   - Day 30+   → this cron auto-flips to 'No-go'
 *
 * Calificado / Convertido / No-go / Nuevo are NOT touched — only Contactado.
 * Anything past Contactado has had a human decision and stays where the
 * salesperson left it.
 *
 * Single SQL UPDATE; idempotent.
 */
@Component
public class LeadAgingScheduler {

    private static final Logger logger = LoggerFactory.getLogger(LeadAgingScheduler.class);
    private static final int AGING_DAYS = 30;

    private final JdbcTemplate jdbcTemplate;

    public LeadAgingScheduler(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    // 02:30 UTC every day, after the contact-funnel crons (03:00 / 04:00).
    @Scheduled(cron = "0 30 2 * * *")
    public void ageOutStaleContactado() {
        runOnce();
    }

    public RunResult runOnce() {
        int flipped = jdbcTemplate.update("""
            UPDATE beworking.leads
               SET status = 'No-go',
                   status_changed_at = NOW()
             WHERE status = 'Contactado'
               AND status_changed_at IS NOT NULL
               AND status_changed_at < NOW() - (? * INTERVAL '1 day')
            """, AGING_DAYS);

        if (flipped > 0) {
            logger.info("Lead aging cron: flipped {} stale 'Contactado' leads to 'No-go' (older than {} days)",
                flipped, AGING_DAYS);
        }
        return new RunResult(flipped);
    }

    public record RunResult(int flipped) {}
}
