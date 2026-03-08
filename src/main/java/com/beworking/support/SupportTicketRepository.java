package com.beworking.support;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface SupportTicketRepository extends JpaRepository<SupportTicket, Long> {
    List<SupportTicket> findByTenantIdOrderByCreatedAtDesc(Long tenantId);
    List<SupportTicket> findByStatusOrderByCreatedAtAsc(String status);
    List<SupportTicket> findAllByOrderByCreatedAtDesc();
}
