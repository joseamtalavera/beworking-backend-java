package com.beworking.mailroom;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MailroomDocumentRepository extends JpaRepository<MailroomDocument, UUID> {
    List<MailroomDocument> findTop100ByOrderByReceivedAtDescCreatedAtDesc();

    List<MailroomDocument> findTop100ByTenantIdOrderByReceivedAtDescCreatedAtDesc(UUID tenantId);

    Optional<MailroomDocument> findByPickupCode(String pickupCode);

    boolean existsByPickupCode(String pickupCode);

    default List<MailroomDocument> findRecentDocuments(UUID tenantId) {
        if (tenantId != null) {
            return findTop100ByTenantIdOrderByReceivedAtDescCreatedAtDesc(tenantId);
        }
        return findTop100ByOrderByReceivedAtDescCreatedAtDesc();
    }
}
