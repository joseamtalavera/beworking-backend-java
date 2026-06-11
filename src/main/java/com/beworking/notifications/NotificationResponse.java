package com.beworking.notifications;

import java.time.Instant;
import java.util.UUID;

public record NotificationResponse(
        UUID id,
        String contactEmail,
        UUID tenantId,
        String subject,
        String body,
        String status,
        String createdBy,
        Instant sentAt,
        Instant readAt,
        Instant acknowledgedAt,
        Instant createdAt
) {
    public static NotificationResponse fromEntity(Notification n) {
        return new NotificationResponse(
                n.getId(),
                n.getContactEmail(),
                n.getTenantId(),
                n.getSubject(),
                n.getBody(),
                n.getStatus() != null ? n.getStatus().name().toLowerCase() : null,
                n.getCreatedBy(),
                n.getSentAt(),
                n.getReadAt(),
                n.getAcknowledgedAt(),
                n.getCreatedAt()
        );
    }
}
