package com.beworking.contacts;

import com.beworking.auth.EmailService;
import java.time.LocalDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Hourly job that recovers cart-abandonment leads.
 *
 * RegisterService.registerPendingUser already creates the ContactProfile with
 * status='Pendiente Pago' when a user starts the signup but hasn't completed
 * payment. When they finish payment, the status flips to 'Activo'. Anyone
 * still sitting at 'Pendiente Pago' more than {@value #GRACE_MINUTES} minutes
 * later is treated as abandoned and gets a recovery email
 * (BCC: info@be-working.com so the team can pick up the thread).
 *
 * Each row is stamped via {@code abandonment_email_sent_at} before the email
 * dispatches, so a retry never double-sends. If a user later completes
 * payment, the status flip to 'Activo' takes them out of this query naturally.
 */
@Component
public class AbandonmentRecoveryScheduler {

    private static final Logger logger = LoggerFactory.getLogger(AbandonmentRecoveryScheduler.class);
    private static final String ABANDONED_STATUS = "Pendiente Pago";
    private static final long GRACE_MINUTES = 30;

    private final ContactProfileRepository contactProfileRepository;
    private final EmailService emailService;

    public AbandonmentRecoveryScheduler(ContactProfileRepository contactProfileRepository,
                                        EmailService emailService) {
        this.contactProfileRepository = contactProfileRepository;
        this.emailService = emailService;
    }

    // Runs at minute 0 of every hour.
    @Scheduled(cron = "0 0 * * * *")
    public void sendPendingRecoveryEmails() {
        List<ContactProfile> targets = contactProfileRepository
            .findByStatusAndAbandonmentEmailSentAtIsNull(ABANDONED_STATUS);

        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(GRACE_MINUTES);
        int sent = 0;
        int skipped = 0;

        for (ContactProfile cp : targets) {
            // Wait at least GRACE_MINUTES after creation so the user has a fair
            // chance to finish payment before we ping them.
            if (cp.getCreatedAt() != null && cp.getCreatedAt().isAfter(cutoff)) {
                continue;
            }
            String email = cp.getEmailPrimary();
            if (email == null || email.isBlank()) {
                skipped++;
                continue;
            }
            cp.setAbandonmentEmailSentAt(LocalDateTime.now());
            contactProfileRepository.save(cp);
            emailService.sendAbandonmentRecoveryEmail(email, cp.getName());
            sent++;
        }

        if (sent > 0 || skipped > 0) {
            logger.info("Abandonment recovery cron: sent={} skipped={} candidates={}",
                sent, skipped, targets.size());
        }
    }
}
