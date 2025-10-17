package com.beworking.mailroom;

import com.beworking.auth.EmailService;
import com.beworking.storage.FileStorageService;
import com.beworking.storage.StoredFile;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@Service
public class MailroomDocumentService {

    private static final String[] DEFAULT_AVATAR_COLORS = {
            "#facc15",
            "#38bdf8",
            "#f472b6",
            "#c4b5fd",
            "#f97316"
    };

    private final MailroomDocumentRepository repository;
    private final FileStorageService fileStorageService;
    private final EmailService emailService;

    public MailroomDocumentService(MailroomDocumentRepository repository, FileStorageService fileStorageService, EmailService emailService) {
        this.repository = repository;
        this.fileStorageService = fileStorageService;
        this.emailService = emailService;
    }

    public List<MailroomDocumentResponse> listRecentDocuments(UUID tenantId, String contactEmail) {
        return repository.findRecentDocuments(tenantId).stream()
                .filter(doc -> {
                    if (tenantId == null && contactEmail == null) {
                        return true;
                    }
                    if (tenantId != null && tenantId.equals(doc.getTenantId())) {
                        return true;
                    }
                    if (contactEmail != null && doc.getContactEmail() != null) {
                        return contactEmail.equalsIgnoreCase(doc.getContactEmail());
                    }
                    return false;
                })
                .map(MailroomDocumentResponse::fromEntity)
                .toList();
    }

    @Transactional
    public MailroomDocumentResponse createDocument(
            MultipartFile file,
            String title,
            String sender,
            Instant receivedAt,
            String tenantId,
            Integer pages,
            String avatarColor,
            String contactEmail
    ) {
        StoredFile storedFile = fileStorageService.store(file);

        MailroomDocument document = new MailroomDocument();
        document.setTitle(resolveTitle(title, storedFile.originalFileName()));
        document.setSender(StringUtils.hasText(sender) ? sender : null);
        document.setContactEmail(StringUtils.hasText(contactEmail) ? contactEmail : null);
        document.setReceivedAt(Optional.ofNullable(receivedAt).orElse(Instant.now()));
        document.setStatus(MailroomDocumentStatus.SCANNED);
        document.setPages(pages);
        document.setAvatarColor(resolveAvatarColor(avatarColor));
        document.setStoredFileName(storedFile.storedFileName());
        document.setOriginalFileName(storedFile.originalFileName());
        document.setContentType(storedFile.contentType());
        document.setFileSizeBytes(storedFile.sizeInBytes());

        if (StringUtils.hasText(tenantId)) {
            try {
                document.setTenantId(UUID.fromString(tenantId));
            } catch (IllegalArgumentException ignored) {
                // leave tenant unset if parsing fails
            }
        }

        MailroomDocument persisted = repository.save(document);
        return MailroomDocumentResponse.fromEntity(persisted);
    }

    @Transactional
    public MailroomDocumentResponse markDocumentNotified(UUID documentId) {
        MailroomDocument document = getDocumentOrThrow(documentId);
        document.setStatus(MailroomDocumentStatus.NOTIFIED);
        document.setLastNotifiedAt(Instant.now());
        MailroomDocument saved = repository.save(document);
        
        // Send email notification
        sendDocumentNotificationEmail(document);
        
        return MailroomDocumentResponse.fromEntity(saved);
    }

    @Transactional
    public MailroomDocumentResponse markDocumentViewed(UUID documentId) {
        MailroomDocument document = getDocumentOrThrow(documentId);
        document.setStatus(MailroomDocumentStatus.VIEWED);
        MailroomDocument saved = repository.save(document);
        return MailroomDocumentResponse.fromEntity(saved);
    }

    private String resolveTitle(String providedTitle, String originalFilename) {
        if (StringUtils.hasText(providedTitle)) {
            return providedTitle.trim();
        }
        if (StringUtils.hasText(originalFilename)) {
            return originalFilename;
        }
        return "Scanned document";
    }

    private String resolveAvatarColor(String providedColor) {
        if (StringUtils.hasText(providedColor)) {
            return providedColor;
        }
        int index = Math.abs(UUID.randomUUID().hashCode()) % DEFAULT_AVATAR_COLORS.length;
        return DEFAULT_AVATAR_COLORS[index];
    }

    private MailroomDocument getDocumentOrThrow(UUID documentId) {
        return repository.findById(documentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Mailroom document not found"));
    }

    public MailroomDocumentDownload getDocumentDownload(UUID documentId) {
        MailroomDocument document = getDocumentOrThrow(documentId);
        var resource = fileStorageService.loadAsResource(document.getStoredFileName());
        String contentType = StringUtils.hasText(document.getContentType())
                ? document.getContentType()
                : MediaType.APPLICATION_OCTET_STREAM_VALUE;
        String originalName = StringUtils.hasText(document.getOriginalFileName())
                ? document.getOriginalFileName()
                : document.getTitle();
        return new MailroomDocumentDownload(resource, originalName, contentType);
    }

    private void sendDocumentNotificationEmail(MailroomDocument document) {
        // Extract contact email from the document
        String contactEmail = extractContactEmail(document);
        
        if (!StringUtils.hasText(contactEmail)) {
            // Log warning but don't fail the operation
            System.err.println("Warning: No contact email found for document " + document.getId() + ", skipping email notification");
            return;
        }

        String subject = "New Document Available - " + document.getTitle();
        String htmlContent = createDocumentNotificationEmailHtml(document);
        
        try {
            emailService.sendHtml(contactEmail, subject, htmlContent);
            System.out.println("Document notification email sent successfully to: " + contactEmail);
        } catch (Exception e) {
            System.err.println("Failed to send document notification email to " + contactEmail + ": " + e.getMessage());
            // Don't rethrow - we don't want email failures to break the notification process
        }
    }

    private String extractContactEmail(MailroomDocument document) {
        // Use the contactEmail field which contains the actual contact's email address
        String contactEmail = document.getContactEmail();
        
        if (StringUtils.hasText(contactEmail)) {
            return contactEmail;
        }
        
        // Fallback: if no contactEmail, try to use sender if it looks like an email
        String sender = document.getSender();
        if (StringUtils.hasText(sender) && sender.contains("@")) {
            return sender;
        }
        
        // No valid email found
        return null;
    }

    private String createDocumentNotificationEmailHtml(MailroomDocument document) {
        return String.format("""
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="UTF-8">
                <title>New Document Available</title>
                <style>
                    body { font-family: Arial, sans-serif; line-height: 1.6; color: #333; }
                    .container { max-width: 600px; margin: 0 auto; padding: 20px; }
                    .header { background-color: #22c55e; color: white; padding: 20px; text-align: center; border-radius: 8px 8px 0 0; }
                    .content { background-color: #f9f9f9; padding: 20px; border-radius: 0 0 8px 8px; }
                    .document-info { background-color: white; padding: 15px; border-radius: 5px; margin: 15px 0; border-left: 4px solid #22c55e; }
                    .button { display: inline-block; background-color: #22c55e; color: white; padding: 12px 24px; text-decoration: none; border-radius: 5px; margin: 10px 0; }
                    .footer { text-align: center; margin-top: 20px; color: #666; font-size: 12px; }
                </style>
            </head>
            <body>
                <div class="container">
                    <div class="header">
                        <h1>📄 New Document Available</h1>
                    </div>
                    <div class="content">
                        <p>Hello,</p>
                        <p>A new document has been uploaded to your virtual office mailbox and is ready for review.</p>
                        
                        <div class="document-info">
                            <h3>Document Details:</h3>
                            <p><strong>Title:</strong> %s</p>
                            <p><strong>Uploaded:</strong> %s</p>
                            <p><strong>File Type:</strong> %s</p>
                            %s
                        </div>
                        
                        <p>You can access your virtual office mailbox to view and download this document.</p>
                        
                        <p>Best regards,<br>BeWorking Team</p>
                    </div>
                    <div class="footer">
                        <p>This is an automated notification from BeWorking Virtual Office</p>
                    </div>
                </div>
            </body>
            </html>
            """,
            document.getTitle() != null ? document.getTitle() : "Untitled Document",
            document.getCreatedAt() != null ? document.getCreatedAt().toString() : "Unknown",
            document.getContentType() != null ? document.getContentType() : "Unknown",
            document.getSender() != null ? "<p><strong>From:</strong> " + document.getSender() + "</p>" : ""
        );
    }
}
