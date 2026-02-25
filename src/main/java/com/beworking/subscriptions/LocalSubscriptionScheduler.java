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
        String currentMonth = YearMonth.now().toString(); // "2026-03"
        logger.info("LocalSubscriptionScheduler: generating bank_transfer invoices for month={}", currentMonth);

        List<Subscription> dueSubscriptions = subscriptionService.findBankTransferDueForMonth(currentMonth);
        int success = 0;
        int failed = 0;

        for (Subscription sub : dueSubscriptions) {
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

        logger.info("LocalSubscriptionScheduler: month={} total={} success={} failed={}",
            currentMonth, dueSubscriptions.size(), success, failed);
    }
}
