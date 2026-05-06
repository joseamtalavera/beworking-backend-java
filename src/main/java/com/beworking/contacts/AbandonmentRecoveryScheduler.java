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
 * Hourly recovery cron for the funnel.
 *
 * RegisterService writes status='Potencial' when a user starts a paid flow
 * (OV signup / room booking) and hasn't yet generated an invoice. When their
 * payment lands, InvoiceService flips them to 'Activo' — which removes them
 * from this query naturally.
 *
 * Anyone still at 'Potencial' gets a four-touch recovery sequence:
 *   #1  T+30 min  — quick check-in / WhatsApp offer (existing template)
 *   #2  T+1 day   — softer nudge with help angle
 *   #3  T+3 days  — social proof / testimonial
 *   #4  T+6 days  — last chance
 *
 * After 7 days the {@link PotencialAgingScheduler} flips them to 'Inactivo'
 * and the recovery sequence stops.
 *
 * Each send increments {@code abandonment_email_count} and stamps
 * {@code last_recovery_email_at} — guarantees no double-send and lets us
 * pick the right next template on the next pass.
 */
@Component
public class AbandonmentRecoveryScheduler {

    private static final Logger logger = LoggerFactory.getLogger(AbandonmentRecoveryScheduler.class);
    private static final String TARGET_STATUS = "Potencial";
    private static final int MAX_EMAILS = 4;
    private static final Duration RECOVERY_WINDOW = Duration.ofDays(7);

    /**
     * Minimum elapsed time since {@code created_at} for each email number.
     * Index = number of emails already sent (0 → about to send #1, etc.).
     */
    private static final Duration[] DELAY_AFTER_CREATION = {
        Duration.ofMinutes(30),  // #1
        Duration.ofDays(1),       // #2
        Duration.ofDays(3),       // #3
        Duration.ofDays(6)        // #4
    };

    private final ContactProfileRepository contactProfileRepository;
    private final EmailService emailService;

    public AbandonmentRecoveryScheduler(ContactProfileRepository contactProfileRepository,
                                        EmailService emailService) {
        this.contactProfileRepository = contactProfileRepository;
        this.emailService = emailService;
    }

    @Scheduled(cron = "0 0 * * * *")
    public void sendRecoveryEmails() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime windowStart = now.minus(RECOVERY_WINDOW);

        List<ContactProfile> candidates = contactProfileRepository
            .findByStatusAndCreatedAtGreaterThanEqualAndAbandonmentEmailCountLessThan(
                TARGET_STATUS, windowStart, MAX_EMAILS);

        int sent = 0;
        int skipped = 0;

        for (ContactProfile cp : candidates) {
            int alreadySent = cp.getAbandonmentEmailCount();
            if (alreadySent >= MAX_EMAILS) {
                continue;
            }
            LocalDateTime createdAt = cp.getCreatedAt();
            if (createdAt == null) {
                skipped++;
                continue;
            }
            Duration elapsedSinceCreation = Duration.between(createdAt, now);
            if (elapsedSinceCreation.compareTo(DELAY_AFTER_CREATION[alreadySent]) < 0) {
                continue;
            }
            String email = cp.getEmailPrimary();
            if (email == null || email.isBlank()) {
                skipped++;
                continue;
            }

            int templateNumber = alreadySent + 1;
            cp.setAbandonmentEmailCount(templateNumber);
            cp.setLastRecoveryEmailAt(now);
            if (templateNumber == 1) {
                cp.setAbandonmentEmailSentAt(now);
            }
            contactProfileRepository.save(cp);

            emailService.sendRecoveryEmail(email, cp.getName(), templateNumber);
            sent++;
        }

        if (sent > 0 || skipped > 0) {
            logger.info("Recovery cron: sent={} skipped={} candidates={}",
                sent, skipped, candidates.size());
        }
    }
}
