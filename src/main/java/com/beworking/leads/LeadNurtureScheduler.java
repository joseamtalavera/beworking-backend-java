package com.beworking.leads;

import com.beworking.auth.EmailService;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Hourly nurture cron for leads in 'Contactado'.
 *
 * Mirrors the Potencial recovery sequence on contact_profiles, but for leads
 * that filled a contact / inquiry form. The first auto-reply email goes out
 * via LeadEmailListener at form submission. From there:
 *
 *   #1  T+30 min  — quick check-in / WhatsApp offer
 *   #2  T+1 day   — softer nudge
 *   #3  T+3 days  — FAQ angle (cost / sign-up time / cancellation)
 *   #4  T+6 days  — last chance
 *
 * After the 4-email window finishes (day ~7), the lead stays in 'Contactado'
 * silent for the remainder of the grace period. LeadAgingScheduler flips it
 * to 'No-go' at day 30 from status_changed_at.
 *
 * Each send bumps {@code nurture_email_count} and stamps
 * {@code last_nurture_email_at} so the cadence is exact and re-runs are safe.
 */
@Component
public class LeadNurtureScheduler {

    private static final Logger logger = LoggerFactory.getLogger(LeadNurtureScheduler.class);
    private static final String TARGET_STATUS = "Contactado";
    private static final int MAX_EMAILS = 4;
    private static final Duration NURTURE_WINDOW = Duration.ofDays(7);

    /**
     * Minimum elapsed time since {@code status_changed_at} for each email.
     * Index = number of emails already sent (0 → about to send #1, etc.).
     */
    private static final Duration[] DELAY_AFTER_STATUS_CHANGE = {
        Duration.ofMinutes(30),  // #1
        Duration.ofDays(1),       // #2
        Duration.ofDays(3),       // #3
        Duration.ofDays(6)        // #4
    };

    private final LeadRepository leadRepository;
    private final EmailService emailService;

    public LeadNurtureScheduler(LeadRepository leadRepository, EmailService emailService) {
        this.leadRepository = leadRepository;
        this.emailService = emailService;
    }

    @Scheduled(cron = "0 0 * * * *")
    public void sendNurtureEmails() {
        runOnce();
    }

    public RunResult runOnce() {
        Instant now = Instant.now();
        Instant windowStart = now.minus(NURTURE_WINDOW);

        List<Lead> candidates = leadRepository
            .findByStatusAndStatusChangedAtGreaterThanEqualAndNurtureEmailCountLessThan(
                TARGET_STATUS, windowStart, MAX_EMAILS);

        int sent = 0;
        int skipped = 0;

        for (Lead lead : candidates) {
            int alreadySent = lead.getNurtureEmailCount();
            if (alreadySent >= MAX_EMAILS) {
                continue;
            }
            Instant statusChangedAt = lead.getStatusChangedAt();
            if (statusChangedAt == null) {
                skipped++;
                continue;
            }
            Duration elapsed = Duration.between(statusChangedAt, now);
            if (elapsed.compareTo(DELAY_AFTER_STATUS_CHANGE[alreadySent]) < 0) {
                continue;
            }
            String email = lead.getEmail();
            if (email == null || email.isBlank()) {
                skipped++;
                continue;
            }

            int templateNumber = alreadySent + 1;
            lead.setNurtureEmailCount(templateNumber);
            lead.setLastNurtureEmailAt(now);
            leadRepository.save(lead);

            emailService.sendLeadNurtureEmail(email, lead.getName(), templateNumber);
            sent++;
        }

        if (sent > 0 || skipped > 0) {
            logger.info("Lead nurture cron: sent={} skipped={} candidates={}",
                sent, skipped, candidates.size());
        }
        return new RunResult(sent, skipped, candidates.size());
    }

    public record RunResult(int sent, int skipped, int totalCandidates) {}
}
