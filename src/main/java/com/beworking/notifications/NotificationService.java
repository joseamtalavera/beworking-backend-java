package com.beworking.notifications;

import com.beworking.auth.EmailService;
import com.beworking.contacts.ContactProfile;
import com.beworking.contacts.ContactProfileService;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

@Service
public class NotificationService {

    private final NotificationRepository repository;
    private final EmailService emailService;
    private final ContactProfileService contactProfileService;
    private final String appBaseUrl;

    public NotificationService(NotificationRepository repository,
                               EmailService emailService,
                               ContactProfileService contactProfileService,
                               @Value("${app.base-url:https://app.be-working.com}") String appBaseUrl) {
        this.repository = repository;
        this.emailService = emailService;
        this.contactProfileService = contactProfileService;
        this.appBaseUrl = appBaseUrl;
    }

    @Transactional
    public NotificationResponse create(String contactEmail, String subject, String body,
                                       UUID tenantId, String createdBy) {
        if (!StringUtils.hasText(contactEmail)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "contactEmail is required");
        }
        if (!StringUtils.hasText(subject) || !StringUtils.hasText(body)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "subject and body are required");
        }

        Notification n = new Notification();
        n.setContactEmail(contactEmail.trim());
        n.setSubject(subject.trim());
        n.setBody(body.trim());
        n.setTenantId(tenantId);
        n.setCreatedBy(createdBy);
        n.setStatus(NotificationStatus.CREATED);
        Notification saved = repository.save(n);

        // Email the client a nudge to read it in-app (best-effort, like mailroom).
        try {
            sendNotificationEmail(saved);
            saved.setStatus(NotificationStatus.SENT);
            saved.setSentAt(Instant.now());
            saved = repository.save(saved);
        } catch (Exception e) {
            System.err.println("Failed to send notification email to " + contactEmail + ": " + e.getMessage());
        }
        return NotificationResponse.fromEntity(saved);
    }

    public List<NotificationResponse> listByContactEmail(String contactEmail) {
        if (!StringUtils.hasText(contactEmail)) {
            return List.of();
        }
        return repository.findByContactEmail(contactEmail).stream()
                .map(NotificationResponse::fromEntity)
                .toList();
    }

    public List<NotificationResponse> listAll() {
        return repository.findTop200ByOrderByCreatedAtDesc().stream()
                .map(NotificationResponse::fromEntity)
                .toList();
    }

    @Transactional
    public NotificationResponse markRead(UUID id) {
        Notification n = getOrThrow(id);
        if (n.getReadAt() == null) {
            n.setReadAt(Instant.now());
        }
        // Don't downgrade an already-acknowledged notification.
        if (n.getStatus() != NotificationStatus.ACKNOWLEDGED) {
            n.setStatus(NotificationStatus.READ);
        }
        return NotificationResponse.fromEntity(repository.save(n));
    }

    @Transactional
    public NotificationResponse acknowledge(UUID id) {
        Notification n = getOrThrow(id);
        Instant now = Instant.now();
        if (n.getReadAt() == null) {
            n.setReadAt(now);
        }
        n.setAcknowledgedAt(now);
        n.setStatus(NotificationStatus.ACKNOWLEDGED);
        return NotificationResponse.fromEntity(repository.save(n));
    }

    @Transactional
    public void delete(UUID id) {
        repository.deleteById(id);
    }

    public boolean isOwnedByEmail(UUID id, String email) {
        if (email == null) {
            return false;
        }
        return repository.findById(id)
                .map(n -> email.equalsIgnoreCase(n.getContactEmail()))
                .orElse(false);
    }

    private Notification getOrThrow(UUID id) {
        return repository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Notification not found"));
    }

    private void sendNotificationEmail(Notification n) {
        String contactEmail = n.getContactEmail();
        Optional<ContactProfile> contact = contactProfileService.findContactByEmail(contactEmail);
        String name = contact.map(c -> firstNonBlank(c.getContactName(), c.getName())).orElse(null);
        String country = contact.map(ContactProfile::getBillingCountry).orElse(null);
        boolean spanish = country == null || country.isBlank()
                || country.equalsIgnoreCase("ES")
                || country.equalsIgnoreCase("España")
                || country.equalsIgnoreCase("Espana")
                || country.equalsIgnoreCase("Spain");

        String subject = spanish
                ? "BeWorking: Tienes una notificación"
                : "BeWorking: You have a notification";
        String html = buildEmailHtml(n, name, spanish);
        emailService.sendHtml(contactEmail, subject, html);
    }

    private String buildEmailHtml(Notification n, String name, boolean spanish) {
        String accent = "#16a34a";
        String greeting = StringUtils.hasText(name)
                ? (spanish ? "Hola " + escapeHtml(name) + "," : "Hi " + escapeHtml(name) + ",")
                : (spanish ? "Hola," : "Hi,");
        String intro = spanish
                ? "Tienes una nueva notificaci&oacute;n formal de BeWorking en tu panel."
                : "You have a new formal notification from BeWorking in your dashboard.";
        String card =
                "<div style=\"background:#ffffff;border:1px solid #e2e8f0;border-left:4px solid " + accent + ";"
                + "border-radius:8px;padding:16px;margin:20px 0;\">"
                + "<div style=\"font-size:16px;font-weight:600;color:#0f172a;\">" + escapeHtml(n.getSubject()) + "</div>"
                + "</div>";
        String mailboxUrl = StringUtils.hasText(appBaseUrl) ? appBaseUrl : "https://app.be-working.com";
        String cta = spanish ? "Ver mi notificaci&oacute;n" : "View my notification";
        String closing = spanish ? "Un saludo,<br>El equipo de BeWorking"
                                 : "Best regards,<br>The BeWorking team";
        String footer = spanish ? "Notificaci&oacute;n autom&aacute;tica de BeWorking &middot; Domicilio Fiscal"
                                : "Automated notification from BeWorking &middot; Business Address";

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
                + "<a href=\"" + mailboxUrl + "\" style=\"display:inline-block;padding:12px 28px;color:#ffffff;"
                + "font-weight:600;font-size:15px;text-decoration:none;border-radius:8px;\">" + cta + "</a>"
                + "</td></tr></table>"
                + "<p style=\"margin:0;color:#334155;\">" + closing + "</p></td></tr>"
                + "<tr><td style=\"padding:18px 32px;text-align:center;color:#94a3b8;font-size:12px;\">"
                + footer + "</td></tr></table></body></html>";
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

    private String escapeHtml(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }
}
