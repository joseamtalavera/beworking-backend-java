package com.beworking.contacts;

import com.beworking.auth.EmailService;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Re-engagement campaign for Inactivo contacts.
 *
 * Once a contact is Inactivo (aged-out Potencial, cancelled sub, or 12-month
 * dormant Activo), this job sends a soft "long time no see" email every
 * {@value #INTERVAL_MONTHS} months, capped at {@value #MAX_EMAILS} attempts.
 *
 * After the cap (~18 months total), they're really gone. Admin can clear
 * last_reengagement_email_at + reset reengagement_email_count to start
 * the sequence over.
 *
 * Anyone who reactivates (becomes Activo again — new invoice, new sub) is
 * naturally removed from this query because we filter by status='Inactivo'.
 */
@Component
public class InactivoReengagementScheduler {

    private static final Logger logger = LoggerFactory.getLogger(InactivoReengagementScheduler.class);
    private static final String TARGET_STATUS = "Inactivo";
    private static final int MAX_EMAILS = 3;
    private static final int INTERVAL_MONTHS = 6;
    private static final Duration INTERVAL = Duration.ofDays(INTERVAL_MONTHS * 30L);

    private final ContactProfileRepository contactProfileRepository;
    private final EmailService emailService;

    public InactivoReengagementScheduler(ContactProfileRepository contactProfileRepository,
                                         EmailService emailService) {
        this.contactProfileRepository = contactProfileRepository;
        this.emailService = emailService;
    }

    // 02:00 UTC on the 1st of each month.
    @Scheduled(cron = "0 0 2 1 * *")
    public void sendReengagementEmails() {
        RunResult result = runOnce();
        // Unattended cron: reengagement emails go 1:1 to the customer (no BCC).
        // Send a single run-summary to info@ so the team knows it fired.
        emailService.sendReengagementCronSummary(
            result.sent(), result.skipped(), result.notDue(), result.totalCandidates());
    }

    /**
     * Same logic as the scheduled run, exposed for the AutomationController
     * "Ejecutar ahora" button. Returns counts so the UI can show feedback.
     */
    public RunResult runOnce() {
        LocalDateTime now = LocalDateTime.now();
        List<ContactProfile> candidates = contactProfileRepository.findByStatus(TARGET_STATUS);

        int sent = 0;
        int skipped = 0;
        int notDue = 0;

        for (ContactProfile cp : candidates) {
            int alreadySent = cp.getReengagementEmailCount();
            if (alreadySent >= MAX_EMAILS) {
                continue;
            }
            LocalDateTime last = cp.getLastReengagementEmailAt();
            if (last != null && Duration.between(last, now).compareTo(INTERVAL) < 0) {
                notDue++;
                continue;
            }
            String email = cp.getEmailPrimary();
            if (email == null || email.isBlank()) {
                skipped++;
                continue;
            }
            cp.setReengagementEmailCount(alreadySent + 1);
            cp.setLastReengagementEmailAt(now);
            contactProfileRepository.save(cp);
            emailService.sendReengagementEmail(email, cp.getName(), cp.getId(), alreadySent + 1);
            sent++;
        }

        if (sent > 0 || skipped > 0) {
            logger.info("Reengagement cron: sent={} skipped={} notDue={} candidates={}",
                sent, skipped, notDue, candidates.size());
        }
        return new RunResult(sent, skipped, notDue, candidates.size());
    }

    public record RunResult(int sent, int skipped, int notDue, int totalCandidates) {}
}
