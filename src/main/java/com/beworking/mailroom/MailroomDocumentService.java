package com.beworking.mailroom;

import com.beworking.auth.EmailService;
import com.beworking.storage.FileStorageService;
import com.beworking.storage.StoredFile;
import java.security.SecureRandom;
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

    private static final String PICKUP_CODE_PREFIX = "BW-";
    private static final String PICKUP_CODE_CHARS = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
    private static final int PICKUP_CODE_LENGTH = 6;
    private static final int MAX_CODE_RETRIES = 10;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

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
            String contactEmail,
            String documentType
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

        // Set document type
        MailroomDocumentType type = MailroomDocumentType.MAIL;
        if (StringUtils.hasText(documentType)) {
            try {
                type = MailroomDocumentType.fromApiValue(documentType);
            } catch (IllegalArgumentException ignored) {
                // default to MAIL
            }
        }
        document.setDocumentType(type);

        // Generate pickup code for packages
        if (type == MailroomDocumentType.PACKAGE) {
            document.setPickupCode(generatePickupCode());
        }

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

    @Transactional
    public MailroomDocumentResponse verifyPickup(String code) {
        if (!StringUtils.hasText(code)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Pickup code is required");
        }
        String normalized = code.trim().toUpperCase();
        MailroomDocument document = repository.findByPickupCode(normalized)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No package found with pickup code: " + normalized));

        if (document.getDocumentType() != MailroomDocumentType.PACKAGE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Document is not a package");
        }
        if (document.getStatus() == MailroomDocumentStatus.PICKED_UP) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Package has already been picked up");
        }

        document.setStatus(MailroomDocumentStatus.PICKED_UP);
        document.setPickedUpAt(Instant.now());
        MailroomDocument saved = repository.save(document);
        return MailroomDocumentResponse.fromEntity(saved);
    }

    @Transactional
    public MailroomDocumentResponse verifyPickupById(UUID documentId) {
        MailroomDocument document = getDocumentOrThrow(documentId);

        if (document.getDocumentType() != MailroomDocumentType.PACKAGE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Document is not a package");
        }
        if (document.getStatus() == MailroomDocumentStatus.PICKED_UP) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Package has already been picked up");
        }

        document.setStatus(MailroomDocumentStatus.PICKED_UP);
        document.setPickedUpAt(Instant.now());
        MailroomDocument saved = repository.save(document);
        return MailroomDocumentResponse.fromEntity(saved);
    }

    String generatePickupCode() {
        for (int attempt = 0; attempt < MAX_CODE_RETRIES; attempt++) {
            StringBuilder sb = new StringBuilder(PICKUP_CODE_PREFIX);
            for (int i = 0; i < PICKUP_CODE_LENGTH; i++) {
                sb.append(PICKUP_CODE_CHARS.charAt(SECURE_RANDOM.nextInt(PICKUP_CODE_CHARS.length())));
            }
            String code = sb.toString();
            if (!repository.existsByPickupCode(code)) {
                return code;
            }
        }
        throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Unable to generate unique pickup code");
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
        String contactEmail = extractContactEmail(document);

        if (!StringUtils.hasText(contactEmail)) {
            System.err.println("Warning: No contact email found for document " + document.getId() + ", skipping email notification");
            return;
        }

        boolean isPackage = document.getDocumentType() == MailroomDocumentType.PACKAGE;
        String subject = isPackage
                ? "Package Ready for Pickup - " + document.getTitle()
                : "New Document Available - " + document.getTitle();
        String htmlContent = isPackage
                ? createPackageNotificationEmailHtml(document)
                : createDocumentNotificationEmailHtml(document);

        try {
            emailService.sendHtml(contactEmail, subject, htmlContent);
            System.out.println("Document notification email sent successfully to: " + contactEmail);
        } catch (Exception e) {
            System.err.println("Failed to send document notification email to " + contactEmail + ": " + e.getMessage());
        }
    }

    private String extractContactEmail(MailroomDocument document) {
        String contactEmail = document.getContactEmail();

        if (StringUtils.hasText(contactEmail)) {
            return contactEmail;
        }

        String sender = document.getSender();
        if (StringUtils.hasText(sender) && sender.contains("@")) {
            return sender;
        }

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
                        <h1>New Document Available</h1>
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

    private String createPackageNotificationEmailHtml(MailroomDocument document) {
        String pickupCode = document.getPickupCode() != null ? document.getPickupCode() : "N/A";
        return String.format("""
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="UTF-8">
                <title>Package Ready for Pickup</title>
                <style>
                    body { font-family: Arial, sans-serif; line-height: 1.6; color: #333; }
                    .container { max-width: 600px; margin: 0 auto; padding: 20px; }
                    .header { background-color: #f97316; color: white; padding: 20px; text-align: center; border-radius: 8px 8px 0 0; }
                    .content { background-color: #f9f9f9; padding: 20px; border-radius: 0 0 8px 8px; }
                    .package-info { background-color: white; padding: 15px; border-radius: 5px; margin: 15px 0; border-left: 4px solid #f97316; }
                    .pickup-code { font-size: 32px; font-weight: bold; color: #f97316; text-align: center; padding: 20px; background-color: #fff7ed; border-radius: 8px; margin: 15px 0; letter-spacing: 4px; }
                    .footer { text-align: center; margin-top: 20px; color: #666; font-size: 12px; }
                </style>
            </head>
            <body>
                <div class="container">
                    <div class="header">
                        <h1>Package Ready for Pickup</h1>
                    </div>
                    <div class="content">
                        <p>Hello,</p>
                        <p>A package has arrived at your virtual office and is ready for pickup.</p>

                        <div class="package-info">
                            <h3>Package Details:</h3>
                            <p><strong>Description:</strong> %s</p>
                            <p><strong>Received:</strong> %s</p>
                            %s
                        </div>

                        <p style="text-align: center; font-weight: bold;">Your Pickup Code:</p>
                        <div class="pickup-code">%s</div>

                        <p>Present this code at the front desk or show the QR code from your dashboard to collect your package.</p>

                        <p>Best regards,<br>BeWorking Team</p>
                    </div>
                    <div class="footer">
                        <p>This is an automated notification from BeWorking Virtual Office</p>
                    </div>
                </div>
            </body>
            </html>
            """,
            document.getTitle() != null ? document.getTitle() : "Package",
            document.getCreatedAt() != null ? document.getCreatedAt().toString() : "Unknown",
            document.getSender() != null ? "<p><strong>From:</strong> " + document.getSender() + "</p>" : "",
            pickupCode
        );
    }
}
