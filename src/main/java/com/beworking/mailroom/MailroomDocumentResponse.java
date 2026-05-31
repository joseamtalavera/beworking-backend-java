  package com.beworking.mailroom;

  import java.time.Instant;
  import java.util.UUID;

  public record MailroomDocumentResponse(
          UUID id,
          UUID tenantId,
          String title,
          String sender,
          String recipient,
          Instant receivedAt,
          String status,
          Instant lastNotifiedAt,
          Integer pages,
          String avatarColor,
          String originalFileName,
          Long fileSizeBytes,
          String contentType,
          String type,
          String pickupCode,
          Instant pickedUpAt,
          String recipientPhone
  ) {
      public static MailroomDocumentResponse fromEntity(MailroomDocument document) {
          return new MailroomDocumentResponse(
                  document.getId(),
                  document.getTenantId(),
                  document.getTitle(),
                  document.getSender(),
                  document.getContactEmail(),
                  document.getReceivedAt(),
                  document.getStatus() != null ? document.getStatus().toApiValue() : null,
                  document.getLastNotifiedAt(),
                  document.getPages(),
                  document.getAvatarColor(),
                  document.getOriginalFileName(),
                  document.getFileSizeBytes(),
                  document.getContentType(),
                  document.getDocumentType() != null ? document.getDocumentType().toApiValue() : "mail",
                  document.getPickupCode(),
                  document.getPickedUpAt(),
                  null
          );
      }

      /** Returns a copy with the recipient's phone filled in (looked up from the contact). */
      public MailroomDocumentResponse withRecipientPhone(String phone) {
          return new MailroomDocumentResponse(
                  id, tenantId, title, sender, recipient, receivedAt, status, lastNotifiedAt,
                  pages, avatarColor, originalFileName, fileSizeBytes, contentType, type,
                  pickupCode, pickedUpAt, phone
          );
      }
  }
  