package com.beworking.subscriptions;

import java.time.YearMonth;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class LocalSubscriptionScheduler {

    private static final Logger logger = LoggerFactory.getLogger(LocalSubscriptionScheduler.class);

    private final SubscriptionService subscriptionService;
    private final SubscriptionRepository subscriptionRepository;

    public LocalSubscriptionScheduler(SubscriptionService subscriptionService,
                                       SubscriptionRepository subscriptionRepository) {
        this.subscriptionService = subscriptionService;
        this.subscriptionRepository = subscriptionRepository;
    }

    /**
     * Runs on the 1st of each month at 01:00 AM.
     * Creates Pendiente invoices for all active bank_transfer subscriptions
     * that haven't been invoiced for the current month yet.
     */
    @Scheduled(cron = "0 0 1 1 * *")
    public void generateBankTransferInvoices() {
        YearMonth current = YearMonth.now();
        String currentMonth = current.toString(); // "2026-03"
        logger.info("LocalSubscriptionScheduler: generating bank_transfer invoices for month={}", currentMonth);

        List<Subscription> dueSubscriptions = subscriptionService.findBankTransferDueForMonth(currentMonth);
        int success = 0;
        int failed = 0;
        int skipped = 0;

        for (Subscription sub : dueSubscriptions) {
            // Check billing interval — skip if not due yet
            if (!isDueForMonth(sub, current)) {
                skipped++;
                logger.info("Skipping subscription {} (contact={}, interval={}, lastInvoiced={}): not due this month",
                    sub.getId(), sub.getContactId(), sub.getBillingInterval(), sub.getLastInvoicedMonth());
                continue;
            }

            try {
                subscriptionService.createBankTransferInvoice(sub, currentMonth);
                sub.setLastInvoicedMonth(currentMonth);
                subscriptionRepository.save(sub);
                success++;
                logger.info("Created bank_transfer invoice for subscription {} (contact={}, month={})",
                    sub.getId(), sub.getContactId(), currentMonth);
            } catch (Exception e) {
                failed++;
                logger.error("Failed to create bank_transfer invoice for subscription {} (contact={}): {}",
                    sub.getId(), sub.getContactId(), e.getMessage(), e);
            }
        }

        logger.info("LocalSubscriptionScheduler: month={} total={} success={} failed={} skipped={}",
            currentMonth, dueSubscriptions.size(), success, failed, skipped);
    }

    /**
     * Determines if a subscription is due for invoicing in the given month,
     * based on its billing_interval and last_invoiced_month.
     */
    private boolean isDueForMonth(Subscription sub, YearMonth current) {
        String interval = sub.getBillingInterval();
        if (interval == null || "month".equals(interval)) {
            return true; // monthly — always due
        }

        String lastInvoiced = sub.getLastInvoicedMonth();
        if (lastInvoiced == null || lastInvoiced.isBlank()) {
            return true; // never invoiced — due now
        }

        YearMonth last = YearMonth.parse(lastInvoiced);
        int monthsBetween = (int) last.until(current, java.time.temporal.ChronoUnit.MONTHS);

        return switch (interval) {
            case "quarter" -> monthsBetween >= 3;
            case "year" -> monthsBetween >= 12;
            default -> true;
        };
    }
}
