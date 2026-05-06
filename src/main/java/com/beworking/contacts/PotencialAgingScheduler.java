package com.beworking.contacts;

import java.time.LocalDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Daily job that ages out contacts stuck at status='Potencial'.
 *
 * The {@link AbandonmentRecoveryScheduler} sends 4 recovery emails over a 7-day
 * window. After that window, anyone still at Potencial is treated as no-longer-
 * actionable and flipped to Inactivo. The recovery cron's window check
 * (created_at within 7d) ensures we stop emailing them at the same time.
 *
 * Anyone who paid in the meantime was already promoted to Activo by
 * InvoiceService — they're filtered out by the status check below.
 */
@Component
public class PotencialAgingScheduler {

    private static final Logger logger = LoggerFactory.getLogger(PotencialAgingScheduler.class);
    private static final String FROM_STATUS = "Potencial";
    private static final String TO_STATUS = "Inactivo";
    private static final int AGING_DAYS = 7;

    private final ContactProfileRepository contactProfileRepository;

    public PotencialAgingScheduler(ContactProfileRepository contactProfileRepository) {
        this.contactProfileRepository = contactProfileRepository;
    }

    // 03:00 UTC every day, after the recovery cron has had its last window.
    @Scheduled(cron = "0 0 3 * * *")
    public void ageOutPotenciales() {
        runOnce();
    }

    public RunResult runOnce() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(AGING_DAYS);
        List<ContactProfile> stale = contactProfileRepository
            .findByStatusAndCreatedAtLessThan(FROM_STATUS, cutoff);

        if (stale.isEmpty()) {
            return new RunResult(0);
        }

        LocalDateTime now = LocalDateTime.now();
        int flipped = 0;
        for (ContactProfile cp : stale) {
            cp.setStatus(TO_STATUS);
            cp.setStatusChangedAt(now);
            contactProfileRepository.save(cp);
            flipped++;
        }
        logger.info("Aging cron: flipped {} contacts from {} to {} (older than {} days)",
            flipped, FROM_STATUS, TO_STATUS, AGING_DAYS);
        return new RunResult(flipped);
    }

    public record RunResult(int flipped) {}
}
