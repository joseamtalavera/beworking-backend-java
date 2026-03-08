package com.beworking.ai;

import com.beworking.subscriptions.SubscriptionRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.Map;

@Service
public class AiUsageService {

    private static final Map<String, Integer> PLAN_LIMITS = Map.of(
        "basis", 50,
        "pro", 200,
        "max", 1000
    );
    private static final int GRACE_QUERIES = 10;

    private final AiUsageRepository aiUsageRepository;
    private final SubscriptionRepository subscriptionRepository;

    public AiUsageService(AiUsageRepository aiUsageRepository, SubscriptionRepository subscriptionRepository) {
        this.aiUsageRepository = aiUsageRepository;
        this.subscriptionRepository = subscriptionRepository;
    }

    /**
     * Returns the plan name for the given tenant based on active subscription amount.
     */
    public String getPlan(Long tenantId) {
        var subs = subscriptionRepository.findByContactIdAndActiveTrue(tenantId);
        if (subs.isEmpty()) return "basis";
        double amount = subs.get(0).getMonthlyAmount().doubleValue();
        if (amount >= 90) return "max";
        if (amount >= 25) return "pro";
        return "basis";
    }

    /**
     * Returns the monthly AI query limit for a tenant's current plan.
     */
    public int getLimit(Long tenantId) {
        String plan = getPlan(tenantId);
        return PLAN_LIMITS.getOrDefault(plan, 50);
    }

    /**
     * Returns the number of AI queries used this month.
     */
    public int getUsed(Long tenantId) {
        LocalDate periodStart = LocalDate.now().withDayOfMonth(1);
        return aiUsageRepository.findByTenantIdAndPeriodStart(tenantId, periodStart)
            .map(AiUsage::getQueriesUsed)
            .orElse(0);
    }

    /**
     * Checks whether the tenant can make an AI query.
     * Allows a grace buffer beyond the limit before hard-blocking.
     */
    public boolean canQuery(Long tenantId) {
        int limit = getLimit(tenantId);
        int used = getUsed(tenantId);
        return used < (limit + GRACE_QUERIES);
    }

    /**
     * Checks if the tenant has exceeded their plan limit (but may still be in grace).
     */
    public boolean isOverLimit(Long tenantId) {
        return getUsed(tenantId) >= getLimit(tenantId);
    }

    /**
     * Increments the query counter for the current month. Creates the record if needed.
     */
    public AiUsage recordQuery(Long tenantId) {
        LocalDate periodStart = LocalDate.now().withDayOfMonth(1);
        LocalDate periodEnd = periodStart.plusMonths(1).minusDays(1);

        AiUsage usage = aiUsageRepository.findByTenantIdAndPeriodStart(tenantId, periodStart)
            .orElseGet(() -> new AiUsage(tenantId, periodStart, periodEnd));

        usage.setQueriesUsed(usage.getQueriesUsed() + 1);
        usage.setUpdatedAt(java.time.LocalDateTime.now());
        return aiUsageRepository.save(usage);
    }

    /**
     * Returns usage summary for the tenant.
     */
    public Map<String, Object> getUsageSummary(Long tenantId) {
        String plan = getPlan(tenantId);
        int limit = PLAN_LIMITS.getOrDefault(plan, 50);
        int used = getUsed(tenantId);
        return Map.of(
            "plan", plan,
            "limit", limit,
            "used", used,
            "remaining", Math.max(0, limit - used),
            "overLimit", used >= limit
        );
    }
}
