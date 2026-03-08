package com.beworking.support;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {
    List<ChatMessage> findByTenantIdOrderByCreatedAtAsc(Long tenantId);
    List<ChatMessage> findTop50ByTenantIdOrderByCreatedAtDesc(Long tenantId);
}
