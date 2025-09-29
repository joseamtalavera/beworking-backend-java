package com.beworking.mailroom;

import java.time.Instant;
import java.util.UUID;

public record MailroomDocumentResponse(
        UUID id,
        UUID tenantId,
        String title,
        String sender,
        Instant receivedAt,
        String status,
        Instant lastNotifiedAt,
        Integer pages,
        String avatarColor,
        String originalFileName,
        Long fileSizeBytes,
        String contentType
) {
    public static MailroomDocumentResponse fromEntity(MailroomDocument document) {
        return new MailroomDocumentResponse(
                document.getId(),
                document.getTenantId(),
                document.getTitle(),
                document.getSender(),
                document.getReceivedAt(),
                document.getStatus() != null ? document.getStatus().toApiValue() : null,
                document.getLastNotifiedAt(),
                document.getPages(),
                document.getAvatarColor(),
                document.getOriginalFileName(),
                document.getFileSizeBytes(),
                document.getContentType()
        );
    }
}
