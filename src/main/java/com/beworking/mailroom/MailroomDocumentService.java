package com.beworking.mailroom;

import com.beworking.auth.EmailService;
import com.beworking.contacts.ContactProfile;
import com.beworking.contacts.ContactProfileService;
import com.beworking.storage.FileStorage;
import com.beworking.storage.StoredFile;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.HashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
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
    private final FileStorage fileStorageService;
    private final EmailService emailService;
    private final ContactProfileService contactProfileService;
    private final String appBaseUrl;

    public MailroomDocumentService(MailroomDocumentRepository repository, FileStorage fileStorageService,
            EmailService emailService, ContactProfileService contactProfileService,
            @Value("${app.base-url:https://app.be-working.com}") String appBaseUrl) {
        this.repository = repository;
        this.fileStorageService = fileStorageService;
        this.emailService = emailService;
        this.contactProfileService = contactProfileService;
        this.appBaseUrl = appBaseUrl;
    }

    public List<MailroomDocumentResponse> listRecentDocuments(UUID tenantId, String contactEmail) {
        Map<String, String> phoneByEmail = new HashMap<>();
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
                .map(doc -> {
                    MailroomDocumentResponse response = MailroomDocumentResponse.fromEntity(doc);
                    String email = doc.getContactEmail();
                    if (email == null || email.isBlank()) {
                        return response;
                    }
                    String phone = phoneByEmail.computeIfAbsent(
                            email.toLowerCase(), key -> resolvePhoneForEmail(email));
                    return response.withRecipientPhone(phone);
                })
                .toList();
    }
      
    private String resolvePhoneForEmail(String email) {
        return contactProfileService.findContactByEmail(email)
                .map(contact -> firstNonBlank(
                        contact.getPhonePrimary(),
                        contact.getPhoneSecondary(),
                        contact.getPhoneTertiary(),
                        contact.getPhoneQuaternary(),
                        contact.getRepresentativePhone()))
                .orElse(null);
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
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

    /**
     * True only if the document exists AND its contactEmail matches the given
     * email (case-insensitive). Used to enforce per-user ownership on
     * view/download. A null email or unknown document is never "owned".
     */
    public boolean isDocumentOwnedByEmail(UUID documentId, String email) {
        if (email == null) {
            return false;
        }
        return repository.findById(documentId)
                .map(doc -> email.equalsIgnoreCase(doc.getContactEmail()))
                .orElse(false);
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

    @Transactional
    public void deleteDocument(UUID documentId) {
        MailroomDocument document = getDocumentOrThrow(documentId);
        repository.delete(document);
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

        Optional<ContactProfile> contact = contactProfileService.findContactByEmail(contactEmail);
        String name = contact.map(c -> firstNonBlank(c.getContactName(), c.getName())).orElse(null);
        String country = contact.map(ContactProfile::getBillingCountry).orElse(null);
        boolean spanish = country == null || country.isBlank()
                || country.equalsIgnoreCase("ES")
                || country.equalsIgnoreCase("España")
                || country.equalsIgnoreCase("Espana")
                || country.equalsIgnoreCase("Spain");

        boolean isPackage = document.getDocumentType() == MailroomDocumentType.PACKAGE;
        String subject = isPackage
                ? (spanish ? "📦 BeWorking · Tienes un paquete listo para recoger"
                           : "📦 BeWorking · You have a package ready for pickup")
                : (spanish ? "✉️ BeWorking · Tienes nuevo correo en tu buzón digital"
                           : "✉️ BeWorking · You have new mail in your digital mailbox");
        String htmlContent = isPackage
                ? createPackageNotificationEmailHtml(document, name, spanish)
                : createDocumentNotificationEmailHtml(document, name, spanish);

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

    private String createDocumentNotificationEmailHtml(MailroomDocument document, String name, boolean spanish) {
        String title = escapeHtml(document.getTitle() != null ? document.getTitle()
                : (spanish ? "Documento" : "Document"));
        String received = formatDate(
                document.getReceivedAt() != null ? document.getReceivedAt() : document.getCreatedAt(), spanish);
        String greeting = StringUtils.hasText(name)
                ? (spanish ? "Hola " + escapeHtml(name) + "," : "Hi " + escapeHtml(name) + ",")
                : (spanish ? "Hola," : "Hi,");
        String fromRow = StringUtils.hasText(document.getSender())
                ? "<div style=\"color:#64748b;font-size:13px;margin-top:4px;\">"
                  + (spanish ? "De: " : "From: ") + escapeHtml(document.getSender()) + "</div>"
                : "";
        String mailboxUrl = StringUtils.hasText(appBaseUrl) ? appBaseUrl : "https://app.be-working.com";

        String intro = spanish
                ? "Hemos digitalizado un nuevo documento en tu buz&oacute;n digital de oficina virtual."
                : "We&rsquo;ve added a new document to your virtual office digital mailbox.";
        String card =
                "<div style=\"background:#ffffff;border:1px solid #e2e8f0;border-left:4px solid #16a34a;"
                + "border-radius:8px;padding:16px;margin:20px 0;\">"
                + "<div style=\"font-size:16px;font-weight:600;color:#0f172a;\">&#128196; " + title + "</div>"
                + "<div style=\"color:#64748b;font-size:13px;margin-top:6px;\">"
                + (spanish ? "Recibido: " : "Received: ") + received + "</div>"
                + fromRow
                + "</div>";
        String cta = spanish ? "Ver mi buz&oacute;n" : "View my mailbox";
        String closing = spanish ? "Un saludo,<br>El equipo de BeWorking"
                                 : "Best regards,<br>The BeWorking team";
        String footer = spanish ? "Notificaci&oacute;n autom&aacute;tica de BeWorking &middot; Oficina Virtual"
                                : "Automated notification from BeWorking &middot; Virtual Office";
        return emailShell("#16a34a", greeting, intro, card, mailboxUrl, cta, closing, footer);
    }

    private String createPackageNotificationEmailHtml(MailroomDocument document, String name, boolean spanish) {
        String title = escapeHtml(document.getTitle() != null ? document.getTitle()
                : (spanish ? "Paquete" : "Package"));
        String received = formatDate(
                document.getReceivedAt() != null ? document.getReceivedAt() : document.getCreatedAt(), spanish);
        String code = escapeHtml(document.getPickupCode() != null ? document.getPickupCode() : "N/A");
        String greeting = StringUtils.hasText(name)
                ? (spanish ? "Hola " + escapeHtml(name) + "," : "Hi " + escapeHtml(name) + ",")
                : (spanish ? "Hola," : "Hi,");
        String mailboxUrl = StringUtils.hasText(appBaseUrl) ? appBaseUrl : "https://app.be-working.com";

        String intro = spanish
                ? "Ha llegado un paquete a tu oficina virtual y est&aacute; listo para recoger."
                : "A package has arrived at your virtual office and is ready for pickup.";
        String card =
                "<div style=\"background:#ffffff;border:1px solid #e2e8f0;border-left:4px solid #f97316;"
                + "border-radius:8px;padding:16px;margin:20px 0;\">"
                + "<div style=\"font-size:16px;font-weight:600;color:#0f172a;\">&#128230; " + title + "</div>"
                + "<div style=\"color:#64748b;font-size:13px;margin-top:6px;\">"
                + (spanish ? "Recibido: " : "Received: ") + received + "</div>"
                + "</div>"
                + "<p style=\"margin:0 0 6px;text-align:center;color:#64748b;font-size:13px;\">"
                + (spanish ? "Tu c&oacute;digo de recogida" : "Your pickup code") + "</p>"
                + "<div style=\"text-align:center;font-size:30px;font-weight:700;letter-spacing:6px;color:#f97316;"
                + "background:#fff7ed;border-radius:10px;padding:18px;margin:0 0 10px;\">" + code + "</div>"
                + "<p style=\"margin:0;color:#64748b;font-size:13px;text-align:center;\">"
                + (spanish
                        ? "Presenta este c&oacute;digo en recepci&oacute;n o muestra el c&oacute;digo QR desde tu panel."
                        : "Show this code at the front desk or present the QR code from your dashboard.")
                + "</p>";
        String cta = spanish ? "Ver c&oacute;digo QR" : "View QR code";
        String closing = spanish ? "Un saludo,<br>El equipo de BeWorking"
                                 : "Best regards,<br>The BeWorking team";
        String footer = spanish ? "Notificaci&oacute;n autom&aacute;tica de BeWorking &middot; Oficina Virtual"
                                : "Automated notification from BeWorking &middot; Virtual Office";
        return emailShell("#f97316", greeting, intro, card, mailboxUrl, cta, closing, footer);
    }

    /** Shared branded email shell — header wordmark, body, accent CTA button, footer. */
    private String emailShell(String accent, String greeting, String intro, String card,
            String ctaUrl, String ctaLabel, String closing, String footer) {
        return "<!DOCTYPE html><html><head><meta charset=\"UTF-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\"></head>"
                + "<body style=\"margin:0;padding:24px 0;background:#f1f5f9;\">"
                + "<table role=\"presentation\" align=\"center\" width=\"600\" cellpadding=\"0\" cellspacing=\"0\" "
                + "style=\"max-width:600px;margin:0 auto;font-family:'Segoe UI',Arial,sans-serif;\">"
                + "<tr><td style=\"padding:26px 32px 18px;background:#ffffff;border-radius:12px 12px 0 0;"
                + "border-top:4px solid " + accent + ";\">"
                + "<span style=\"font-size:22px;font-weight:700;color:#0f172a;letter-spacing:-0.5px;\">"
                + "beworking<span style=\"color:" + accent + ";\">.</span></span></td></tr>"
                + "<tr><td style=\"padding:6px 32px 28px;background:#ffffff;color:#334155;font-size:15px;"
                + "line-height:1.6;border-radius:0 0 12px 12px;\">"
                + "<p style=\"font-size:17px;font-weight:600;color:#0f172a;margin:8px 0 12px;\">" + greeting + "</p>"
                + "<p style=\"margin:0;\">" + intro + "</p>"
                + card
                + "<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" style=\"margin:6px 0 24px;\"><tr>"
                + "<td style=\"border-radius:8px;background:" + accent + ";\">"
                + "<a href=\"" + ctaUrl + "\" style=\"display:inline-block;padding:12px 28px;color:#ffffff;"
                + "font-weight:600;font-size:15px;text-decoration:none;border-radius:8px;\">" + ctaLabel + "</a>"
                + "</td></tr></table>"
                + "<p style=\"margin:0;color:#334155;\">" + closing + "</p></td></tr>"
                + "<tr><td style=\"padding:18px 32px;text-align:center;color:#94a3b8;font-size:12px;\">"
                + footer + "</td></tr></table></body></html>";
    }

    private String escapeHtml(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    private String formatDate(Instant instant, boolean spanish) {
        if (instant == null) {
            return spanish ? "Fecha desconocida" : "Unknown date";
        }
        Locale locale = spanish ? new Locale("es", "ES") : Locale.ENGLISH;
        return DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
                .withLocale(locale)
                .withZone(ZoneId.of("Europe/Madrid"))
                .format(instant);
    }
}
