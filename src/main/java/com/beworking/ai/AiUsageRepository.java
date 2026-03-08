package com.beworking.ai;

import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDate;
import java.util.Optional;

public interface AiUsageRepository extends JpaRepository<AiUsage, Long> {
    Optional<AiUsage> findByTenantIdAndPeriodStart(Long tenantId, LocalDate periodStart);
}
