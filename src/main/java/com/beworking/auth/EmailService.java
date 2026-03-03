package com.beworking.auth;

import jakarta.activation.DataSource;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.util.ByteArrayDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
public class EmailService {
    private final JavaMailSender mailSender;
    private static final Logger logger = LoggerFactory.getLogger(EmailService.class);

    @Value("${app.base-url}")
    private String baseUrl;

    @Value("${app.frontend-url}")
    private String frontendUrl;

    public EmailService(JavaMailSender mailSender) {
        this.mailSender = mailSender;
    }

    public void sendConfirmationEmail(String to, String token) {
        String subject = "Confirma tu cuenta \u2014 BeWorking";
        String confirmationLink = baseUrl + "/api/auth/confirm?token=" + token;
        String content = "<!doctype html>"
                + "<html lang=\"es\"><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<title>Confirma tu cuenta</title></head>"
                + "<body style=\"margin:0;padding:0;background:#f7f7f8;-webkit-font-smoothing:antialiased;\">"
                + "<table role=\"presentation\" width=\"100%\" cellspacing=\"0\" cellpadding=\"0\" border=\"0\" style=\"background:#f7f7f8;\">"
                + "<tr><td align=\"center\" style=\"padding:24px 0;\">"
                + "<table role=\"presentation\" width=\"600\" cellspacing=\"0\" cellpadding=\"0\" border=\"0\" style=\"width:600px;max-width:600px;margin:0 auto;\">"
                // ── Green gradient header ──
                + "<tr><td style=\"background:linear-gradient(135deg,#009624 0%,#00c853 100%);padding:40px 32px 32px;color:#ffffff;border-radius:14px 14px 0 0;\">"
                + "<p style=\"margin:0 0 4px;font-family:Arial,Helvetica,sans-serif;font-size:13px;letter-spacing:2px;text-transform:uppercase;opacity:0.85;\">BEWORKING</p>"
                + "<h1 style=\"margin:0;font-family:Arial,Helvetica,sans-serif;font-size:26px;font-weight:700;line-height:1.2;color:#ffffff;\">Confirma tu cuenta</h1>"
                + "</td></tr>"
                // ── Body ──
                + "<tr><td style=\"background:#ffffff;padding:32px;border-radius:0 0 14px 14px;border:1px solid #eee;border-top:0;\">"
                + "<p style=\"margin:0 0 8px;font-family:Arial,Helvetica,sans-serif;font-size:16px;color:#333;\">Hola, gracias por registrarte en <strong>BeWorking</strong>.</p>"
                + "<p style=\"margin:0 0 28px;font-family:Arial,Helvetica,sans-serif;font-size:14px;color:#666;line-height:1.6;\">Solo queda un paso: confirma tu direcci\u00f3n de correo electr\u00f3nico pulsando el bot\u00f3n de abajo.</p>"
                // ── CTA button (table-based for Outlook) ──
                + "<table role=\"presentation\" cellspacing=\"0\" cellpadding=\"0\" border=\"0\" style=\"margin:0 auto;\">"
                + "<tr><td align=\"center\" style=\"border-radius:8px;background:#009624;\">"
                + "<a href=\"" + confirmationLink + "\" style=\"display:inline-block;background:#009624;color:#ffffff;text-decoration:none;font-family:Arial,Helvetica,sans-serif;font-weight:700;font-size:16px;padding:14px 36px;border-radius:8px;\">Confirmar email</a>"
                + "</td></tr></table>"
                // ── Info box ──
                + "<div style=\"margin:28px 0 0;background:#f5faf6;border-radius:10px;padding:16px 20px;border-left:4px solid #009624;\">"
                + "<p style=\"margin:0;font-family:Arial,Helvetica,sans-serif;font-size:13px;color:#666;line-height:1.5;\">Si no has creado una cuenta en BeWorking, puedes ignorar este mensaje.</p>"
                + "</div>"
                // ── Contact ──
                + "<p style=\"margin:28px 0 0;font-family:Arial,Helvetica,sans-serif;font-size:13px;color:#888;text-align:center;\">"
                + "\u00bfNecesitas ayuda? Escr\u00edbenos por WhatsApp: "
                + "<a href=\"https://wa.me/34640369759\" style=\"color:#009624;text-decoration:none;font-weight:600;\">+34 640 369 759</a></p>"
                // ── Footer ──
                + "<div style=\"margin:28px -32px -32px;background:#f9f9f9;padding:16px 32px;text-align:center;border-top:1px solid #eee;border-radius:0 0 14px 14px;\">"
                + "<p style=\"margin:0;font-family:Arial,Helvetica,sans-serif;font-size:12px;color:#aaa;\">\u00a9 BeWorking \u00b7 M\u00e1laga</p>"
                + "</div>"
                + "</td></tr>"
                + "</table>"
                + "</td></tr></table>"
                + "</body></html>";
        try {
            logger.info("Attempting to send confirmation email to {}", to);
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true);
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(content, true);
            mailSender.send(message);
            logger.info("Confirmation email sent successfully to {}", to);
        } catch (Exception e) {
            logger.error("Failed to send confirmation email to {}: {}", to, e.getMessage(), e);
        }
    }

    public void sendPasswordResetEmail(String to, String token) {
        String subject = "Restablecer contrase\u00f1a \u2014 BeWorking";
        String resetLink = frontendUrl + "/main/reset-password?token=" + token;
        String content = "<!doctype html>"
                + "<html lang=\"es\"><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<title>Restablecer contrase\u00f1a</title></head>"
                + "<body style=\"margin:0;padding:0;background:#f7f7f8;-webkit-font-smoothing:antialiased;\">"
                + "<table role=\"presentation\" width=\"100%\" cellspacing=\"0\" cellpadding=\"0\" border=\"0\" style=\"background:#f7f7f8;\">"
                + "<tr><td align=\"center\" style=\"padding:24px 0;\">"
                + "<table role=\"presentation\" width=\"600\" cellspacing=\"0\" cellpadding=\"0\" border=\"0\" style=\"width:600px;max-width:600px;margin:0 auto;\">"
                // ── Green gradient header ──
                + "<tr><td style=\"background:linear-gradient(135deg,#009624 0%,#00c853 100%);padding:40px 32px 32px;color:#ffffff;border-radius:14px 14px 0 0;\">"
                + "<p style=\"margin:0 0 4px;font-family:Arial,Helvetica,sans-serif;font-size:13px;letter-spacing:2px;text-transform:uppercase;opacity:0.85;\">BEWORKING</p>"
                + "<h1 style=\"margin:0;font-family:Arial,Helvetica,sans-serif;font-size:26px;font-weight:700;line-height:1.2;color:#ffffff;\">Restablecer contrase\u00f1a</h1>"
                + "</td></tr>"
                // ── Body ──
                + "<tr><td style=\"background:#ffffff;padding:32px;border-radius:0 0 14px 14px;border:1px solid #eee;border-top:0;\">"
                + "<p style=\"margin:0 0 8px;font-family:Arial,Helvetica,sans-serif;font-size:16px;color:#333;\">Hemos recibido una solicitud para restablecer tu contrase\u00f1a.</p>"
                + "<p style=\"margin:0 0 28px;font-family:Arial,Helvetica,sans-serif;font-size:14px;color:#666;line-height:1.6;\">Pulsa el bot\u00f3n de abajo para crear una nueva contrase\u00f1a. Este enlace caducar\u00e1 en 1 hora.</p>"
                // ── CTA button (table-based for Outlook) ──
                + "<table role=\"presentation\" cellspacing=\"0\" cellpadding=\"0\" border=\"0\" style=\"margin:0 auto;\">"
                + "<tr><td align=\"center\" style=\"border-radius:8px;background:#009624;\">"
                + "<a href=\"" + resetLink + "\" style=\"display:inline-block;background:#009624;color:#ffffff;text-decoration:none;font-family:Arial,Helvetica,sans-serif;font-weight:700;font-size:16px;padding:14px 36px;border-radius:8px;\">Restablecer contrase\u00f1a</a>"
                + "</td></tr></table>"
                // ── Info box ──
                + "<div style=\"margin:28px 0 0;background:#f5faf6;border-radius:10px;padding:16px 20px;border-left:4px solid #009624;\">"
                + "<p style=\"margin:0;font-family:Arial,Helvetica,sans-serif;font-size:13px;color:#666;line-height:1.5;\">Si no has solicitado restablecer tu contrase\u00f1a, puedes ignorar este mensaje. Tu cuenta sigue segura.</p>"
                + "</div>"
                // ── Contact ──
                + "<p style=\"margin:28px 0 0;font-family:Arial,Helvetica,sans-serif;font-size:13px;color:#888;text-align:center;\">"
                + "\u00bfNecesitas ayuda? Escr\u00edbenos por WhatsApp: "
                + "<a href=\"https://wa.me/34640369759\" style=\"color:#009624;text-decoration:none;font-weight:600;\">+34 640 369 759</a></p>"
                // ── Footer ──
                + "<div style=\"margin:28px -32px -32px;background:#f9f9f9;padding:16px 32px;text-align:center;border-top:1px solid #eee;border-radius:0 0 14px 14px;\">"
                + "<p style=\"margin:0;font-family:Arial,Helvetica,sans-serif;font-size:12px;color:#aaa;\">\u00a9 BeWorking \u00b7 M\u00e1laga</p>"
                + "</div>"
                + "</td></tr>"
                + "</table>"
                + "</td></tr></table>"
                + "</body></html>";
        try {
            logger.info("Attempting to send password reset email to {}", to);
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true);
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(content, true);
            mailSender.send(message);
            logger.info("Password reset email sent successfully to {}", to);
        } catch (Exception e) {
            logger.error("Failed to send password reset email to {}: {}", to, e.getMessage(), e);
        }
    }

    public void sendWelcomeEmail(String to, String token) {
        String subject = "Bienvenido a BeWorking \u2014 Configura tu contrase\u00f1a";
        String resetLink = frontendUrl + "/main/reset-password?token=" + token;
        String content = "<!doctype html>"
                + "<html lang=\"es\"><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<title>Configura tu contrase\u00f1a</title></head>"
                + "<body style=\"margin:0;padding:0;background:#f7f7f8;-webkit-font-smoothing:antialiased;\">"
                + "<table role=\"presentation\" width=\"100%\" cellspacing=\"0\" cellpadding=\"0\" border=\"0\" style=\"background:#f7f7f8;\">"
                + "<tr><td align=\"center\" style=\"padding:24px 0;\">"
                + "<table role=\"presentation\" width=\"600\" cellspacing=\"0\" cellpadding=\"0\" border=\"0\" style=\"width:600px;max-width:600px;margin:0 auto;\">"
                + "<tr><td style=\"background:linear-gradient(135deg,#009624 0%,#00c853 100%);padding:40px 32px 32px;color:#ffffff;border-radius:14px 14px 0 0;\">"
                + "<p style=\"margin:0 0 4px;font-family:Arial,Helvetica,sans-serif;font-size:13px;letter-spacing:2px;text-transform:uppercase;opacity:0.85;\">BEWORKING</p>"
                + "<h1 style=\"margin:0;font-family:Arial,Helvetica,sans-serif;font-size:26px;font-weight:700;line-height:1.2;color:#ffffff;\">Bienvenido a BeWorking</h1>"
                + "</td></tr>"
                + "<tr><td style=\"background:#ffffff;padding:32px;border-radius:0 0 14px 14px;border:1px solid #eee;border-top:0;\">"
                + "<p style=\"margin:0 0 8px;font-family:Arial,Helvetica,sans-serif;font-size:16px;color:#333;\">Se ha creado tu cuenta en <strong>BeWorking</strong>.</p>"
                + "<p style=\"margin:0 0 28px;font-family:Arial,Helvetica,sans-serif;font-size:14px;color:#666;line-height:1.6;\">Para comenzar, configura tu contrase\u00f1a pulsando el bot\u00f3n de abajo. Este enlace caducar\u00e1 en 24 horas.</p>"
                + "<table role=\"presentation\" cellspacing=\"0\" cellpadding=\"0\" border=\"0\" style=\"margin:0 auto;\">"
                + "<tr><td align=\"center\" style=\"border-radius:8px;background:#009624;\">"
                + "<a href=\"" + resetLink + "\" style=\"display:inline-block;background:#009624;color:#ffffff;text-decoration:none;font-family:Arial,Helvetica,sans-serif;font-weight:700;font-size:16px;padding:14px 36px;border-radius:8px;\">Configurar contrase\u00f1a</a>"
                + "</td></tr></table>"
                + "<div style=\"margin:28px 0 0;background:#f5faf6;border-radius:10px;padding:16px 20px;border-left:4px solid #009624;\">"
                + "<p style=\"margin:0;font-family:Arial,Helvetica,sans-serif;font-size:13px;color:#666;line-height:1.5;\">Si no esperabas esta invitaci\u00f3n, puedes ignorar este mensaje.</p>"
                + "</div>"
                + "<p style=\"margin:28px 0 0;font-family:Arial,Helvetica,sans-serif;font-size:13px;color:#888;text-align:center;\">"
                + "\u00bfNecesitas ayuda? Escr\u00edbenos por WhatsApp: "
                + "<a href=\"https://wa.me/34640369759\" style=\"color:#009624;text-decoration:none;font-weight:600;\">+34 640 369 759</a></p>"
                + "<div style=\"margin:28px -32px -32px;background:#f9f9f9;padding:16px 32px;text-align:center;border-top:1px solid #eee;border-radius:0 0 14px 14px;\">"
                + "<p style=\"margin:0;font-family:Arial,Helvetica,sans-serif;font-size:12px;color:#aaa;\">\u00a9 BeWorking \u00b7 M\u00e1laga</p>"
                + "</div>"
                + "</td></tr>"
                + "</table>"
                + "</td></tr></table>"
                + "</body></html>";
        try {
            logger.info("Attempting to send welcome email to {}", to);
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true);
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(content, true);
            mailSender.send(message);
            logger.info("Welcome email sent successfully to {}", to);
        } catch (Exception e) {
            logger.error("Failed to send welcome email to {}: {}", to, e.getMessage(), e);
        }
    }

    @Async
    public void sendHtml(String to, String subject, String htmlContent) {
        sendHtml(to, subject, htmlContent, null);
    }

    @Async
    public void sendHtml(String to, String subject, String htmlContent, String replyTo) {
        sendHtmlInternal(to, subject, htmlContent, replyTo, false);
    }

    public String sendHtmlAndReturnMessageId(String to, String subject, String htmlContent) {
        return sendHtmlInternal(to, subject, htmlContent, null, true);
    }

    public String sendHtmlAndReturnMessageId(String to, String subject, String htmlContent, String replyTo) {
        return sendHtmlInternal(to, subject, htmlContent, replyTo, true);
    }

    @Async
    public void sendHtmlWithAttachment(String to, String subject, String htmlContent,
                                       byte[] attachment, String attachmentName) {
        try {
            logger.info("Attempting to send HTML email with attachment to {}", to);
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(htmlContent, true);
            DataSource dataSource = new ByteArrayDataSource(attachment, "application/pdf");
            helper.addAttachment(attachmentName, dataSource);
            mailSender.send(message);
            logger.info("HTML email with attachment sent successfully to {}", to);
        } catch (Exception e) {
            logger.error("Failed to send HTML email with attachment to {}: {}", to, e.getMessage(), e);
        }
    }

    private String sendHtmlInternal(String to, String subject, String htmlContent, String replyTo, boolean returnMessageId) {
        try {
            logger.info("Attempting to send HTML email to {}", to);
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(htmlContent, true);
            if (replyTo != null && !replyTo.isBlank()) {
                helper.setReplyTo(replyTo);
            }
            mailSender.send(message);
            logger.info("HTML email sent successfully to {}", to);
            return returnMessageId ? message.getMessageID() : null;
        } catch (Exception e) {
            logger.error("Failed to send HTML email to {}: {}", to, e.getMessage(), e);
            return null;
        }
    }
}
