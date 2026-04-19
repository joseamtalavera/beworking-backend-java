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

    @Value("${app.mail.from:}")
    private String mailFrom;

    public EmailService(JavaMailSender mailSender) {
        this.mailSender = mailSender;
    }

    private void applyFrom(MimeMessageHelper helper) throws jakarta.mail.MessagingException {
        if (mailFrom != null && !mailFrom.isBlank()) {
            helper.setFrom(mailFrom);
        }
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
            applyFrom(helper);
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
        String resetLink = frontendUrl + "/reset-password?token=" + token;
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
        String plainText = "Restablecer contrase\u00f1a \u2014 BeWorking\n\n"
                + "Hemos recibido una solicitud para restablecer tu contrase\u00f1a.\n\n"
                + "Pulsa el siguiente enlace para crear una nueva contrase\u00f1a (caduca en 1 hora):\n"
                + resetLink + "\n\n"
                + "Si no has solicitado restablecer tu contrase\u00f1a, puedes ignorar este mensaje.\n\n"
                + "\u00bfNecesitas ayuda? WhatsApp: +34 640 369 759\n\n"
                + "\u00a9 BeWorking \u00b7 M\u00e1laga";
        try {
            logger.info("Attempting to send password reset email to {}", to);
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            applyFrom(helper);
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(plainText, content);
            mailSender.send(message);
            logger.info("Password reset email sent successfully to {}", to);
        } catch (Exception e) {
            logger.error("Failed to send password reset email to {}: {}", to, e.getMessage(), e);
        }
    }

    public void sendWelcomeEmail(String to, String token) {
        String subject = "Bienvenido a BeWorking \u2014 Configura tu contrase\u00f1a";
        String resetLink = frontendUrl + "/reset-password?token=" + token;
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
            applyFrom(helper);
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
    public void sendBookingWelcomeEmail(String to, String name, String token) {
        String subject = "Tu cuenta BeWorking está lista";
        String resetLink = frontendUrl + "/reset-password?token=" + token;
        String safeName = name != null ? name : "";
        String content = "<!doctype html>"
                + "<html lang=\"es\"><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<title>Tu cuenta BeWorking</title></head>"
                + "<body style=\"margin:0;padding:0;background:#f7f7f8;-webkit-font-smoothing:antialiased;\">"
                + "<table role=\"presentation\" width=\"100%\" cellspacing=\"0\" cellpadding=\"0\" border=\"0\" style=\"background:#f7f7f8;\">"
                + "<tr><td align=\"center\" style=\"padding:24px 0;\">"
                + "<table role=\"presentation\" width=\"600\" cellspacing=\"0\" cellpadding=\"0\" border=\"0\" style=\"width:600px;max-width:600px;margin:0 auto;\">"
                + "<tr><td style=\"background:linear-gradient(135deg,#009624 0%,#00c853 100%);padding:40px 32px 32px;color:#ffffff;border-radius:14px 14px 0 0;\">"
                + "<p style=\"margin:0 0 4px;font-family:Arial,Helvetica,sans-serif;font-size:13px;letter-spacing:2px;text-transform:uppercase;opacity:0.85;\">BEWORKING</p>"
                + "<h1 style=\"margin:0;font-family:Arial,Helvetica,sans-serif;font-size:26px;font-weight:700;line-height:1.2;color:#ffffff;\">Tu cuenta está lista</h1>"
                + "</td></tr>"
                + "<tr><td style=\"background:#ffffff;padding:32px;border-radius:0 0 14px 14px;border:1px solid #eee;border-top:0;\">"
                + "<p style=\"margin:0 0 8px;font-family:Arial,Helvetica,sans-serif;font-size:16px;color:#333;\">Hola <strong>" + safeName + "</strong>,</p>"
                + "<p style=\"margin:0 0 12px;font-family:Arial,Helvetica,sans-serif;font-size:14px;color:#666;line-height:1.6;\">Hemos creado tu cuenta en <strong>BeWorking</strong> con motivo de tu reserva. Desde tu panel podrás gestionar tus reservas, facturas y mucho más.</p>"
                + "<p style=\"margin:0 0 28px;font-family:Arial,Helvetica,sans-serif;font-size:14px;color:#666;line-height:1.6;\">Configura tu contraseña para acceder. Este enlace caduca en 48 horas.</p>"
                + "<table role=\"presentation\" cellspacing=\"0\" cellpadding=\"0\" border=\"0\" style=\"margin:0 auto;\">"
                + "<tr><td align=\"center\" style=\"border-radius:8px;background:#009624;\">"
                + "<a href=\"" + resetLink + "\" style=\"display:inline-block;background:#009624;color:#ffffff;text-decoration:none;font-family:Arial,Helvetica,sans-serif;font-weight:700;font-size:16px;padding:14px 36px;border-radius:8px;\">Configurar contraseña</a>"
                + "</td></tr></table>"
                + "<div style=\"margin:28px 0 0;background:#f5faf6;border-radius:10px;padding:16px 20px;border-left:4px solid #009624;\">"
                + "<p style=\"margin:0;font-family:Arial,Helvetica,sans-serif;font-size:13px;color:#666;line-height:1.5;\">Si no has realizado ninguna reserva, puedes ignorar este mensaje.</p>"
                + "</div>"
                + "<p style=\"margin:28px 0 0;font-family:Arial,Helvetica,sans-serif;font-size:13px;color:#888;text-align:center;\">"
                + "¿Necesitas ayuda? Escríbenos por WhatsApp: "
                + "<a href=\"https://wa.me/34640369759\" style=\"color:#009624;text-decoration:none;font-weight:600;\">+34 640 369 759</a></p>"
                + "<div style=\"margin:28px -32px -32px;background:#f9f9f9;padding:16px 32px;text-align:center;border-top:1px solid #eee;border-radius:0 0 14px 14px;\">"
                + "<p style=\"margin:0;font-family:Arial,Helvetica,sans-serif;font-size:12px;color:#aaa;\">© BeWorking · Málaga</p>"
                + "</div>"
                + "</td></tr>"
                + "</table>"
                + "</td></tr></table>"
                + "</body></html>";
        try {
            logger.info("Attempting to send booking welcome email to {}", to);
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true);
            applyFrom(helper);
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(content, true);
            mailSender.send(message);
            logger.info("Booking welcome email sent successfully to {}", to);
        } catch (Exception e) {
            logger.error("Failed to send booking welcome email to {}: {}", to, e.getMessage(), e);
        }
    }

    @Async
    public void sendSubscriptionWelcomeEmail(String to, String name, String plan, String location) {
        String planLabel = plan != null ? switch (plan.toLowerCase()) {
            case "basic" -> "Basic";
            case "pro" -> "Pro";
            case "max" -> "Max";
            default -> plan;
        } : "Basic";

        String locationLabel = location != null ? switch (location.toLowerCase()) {
            case "malaga" -> "Málaga";
            case "sevilla" -> "Sevilla";
            default -> location;
        } : "";

        String loginUrl = frontendUrl + "/login";

        String subject = "Bienvenido a BeWorking — Tu suscripción está activa";
        String content = "<!doctype html>"
                + "<html lang=\"es\"><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<title>Bienvenido a BeWorking</title></head>"
                + "<body style=\"margin:0;padding:0;background:#f7f7f8;-webkit-font-smoothing:antialiased;\">"
                + "<table role=\"presentation\" width=\"100%\" cellspacing=\"0\" cellpadding=\"0\" border=\"0\" style=\"background:#f7f7f8;\">"
                + "<tr><td align=\"center\" style=\"padding:24px 0;\">"
                + "<table role=\"presentation\" width=\"600\" cellspacing=\"0\" cellpadding=\"0\" border=\"0\" style=\"width:600px;max-width:600px;margin:0 auto;\">"
                // Header
                + "<tr><td style=\"background:linear-gradient(135deg,#009624 0%,#00c853 100%);padding:40px 32px 32px;color:#ffffff;border-radius:14px 14px 0 0;\">"
                + "<p style=\"margin:0 0 4px;font-family:Arial,Helvetica,sans-serif;font-size:13px;letter-spacing:2px;text-transform:uppercase;opacity:0.85;\">BEWORKING</p>"
                + "<h1 style=\"margin:0;font-family:Arial,Helvetica,sans-serif;font-size:26px;font-weight:700;line-height:1.2;color:#ffffff;\">Bienvenido, " + (name != null ? name : "") + "</h1>"
                + "</td></tr>"
                // Body
                + "<tr><td style=\"background:#ffffff;padding:32px;border-radius:0 0 14px 14px;border:1px solid #eee;border-top:0;\">"
                + "<p style=\"margin:0 0 8px;font-family:Arial,Helvetica,sans-serif;font-size:16px;color:#333;\">Tu registro en <strong>BeWorking</strong> se ha completado correctamente.</p>"
                + "<p style=\"margin:0 0 20px;font-family:Arial,Helvetica,sans-serif;font-size:14px;color:#666;line-height:1.6;\">Tu suscripci\u00f3n ya est\u00e1 activa. Disfruta de todos los servicios de tu oficina virtual.</p>"
                // Plan info box
                + "<div style=\"margin:0 0 20px;background:#f5faf6;border-radius:10px;padding:16px 20px;border-left:4px solid #009624;\">"
                + "<p style=\"margin:0 0 6px;font-family:Arial,Helvetica,sans-serif;font-size:13px;color:#666;\"><strong>Plan:</strong> " + planLabel + "</p>"
                + (locationLabel.isEmpty() ? "" : "<p style=\"margin:0;font-family:Arial,Helvetica,sans-serif;font-size:13px;color:#666;\"><strong>Sede:</strong> " + locationLabel + "</p>")
                + "<p style=\"margin:6px 0 0;font-family:Arial,Helvetica,sans-serif;font-size:13px;color:#666;\"><strong>Estado:</strong> Activa</p>"
                + "</div>"
                + "<p style=\"margin:0 0 28px;font-family:Arial,Helvetica,sans-serif;font-size:14px;color:#666;line-height:1.6;\">Ya puedes acceder a tu panel de control con el email y contrase\u00f1a que has elegido.</p>"
                // CTA button
                + "<table role=\"presentation\" cellspacing=\"0\" cellpadding=\"0\" border=\"0\" style=\"margin:0 auto;\">"
                + "<tr><td align=\"center\" style=\"border-radius:8px;background:#009624;\">"
                + "<a href=\"" + loginUrl + "\" style=\"display:inline-block;background:#009624;color:#ffffff;text-decoration:none;font-family:Arial,Helvetica,sans-serif;font-weight:700;font-size:16px;padding:14px 36px;border-radius:8px;\">Acceder a mi cuenta</a>"
                + "</td></tr></table>"
                // Contact
                + "<p style=\"margin:28px 0 0;font-family:Arial,Helvetica,sans-serif;font-size:13px;color:#888;text-align:center;\">"
                + "\u00bfNecesitas ayuda? Escr\u00edbenos por WhatsApp: "
                + "<a href=\"https://wa.me/34640369759\" style=\"color:#009624;text-decoration:none;font-weight:600;\">+34 640 369 759</a></p>"
                // Footer
                + "<div style=\"margin:28px -32px -32px;background:#f9f9f9;padding:16px 32px;text-align:center;border-top:1px solid #eee;border-radius:0 0 14px 14px;\">"
                + "<p style=\"margin:0;font-family:Arial,Helvetica,sans-serif;font-size:12px;color:#aaa;\">\u00a9 BeWorking \u00b7 M\u00e1laga</p>"
                + "</div>"
                + "</td></tr>"
                + "</table>"
                + "</td></tr></table>"
                + "</body></html>";
        try {
            logger.info("Attempting to send trial welcome email to {}", to);
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true);
            applyFrom(helper);
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(content, true);
            mailSender.send(message);
            logger.info("Trial welcome email sent successfully to {}", to);
        } catch (Exception e) {
            logger.error("Failed to send trial welcome email to {}: {}", to, e.getMessage(), e);
        }
    }

    @Async
    public void sendRegistrationAdminNotification(String name, String email, String phone,
                                                   String company, String taxId, String plan, String location) {
        String planLabel = plan != null ? switch (plan.toLowerCase()) {
            case "basic" -> "Basic — 15 €/mes";
            case "pro" -> "Pro — 25 €/mes";
            case "max" -> "Max — 90 €/mes";
            default -> plan;
        } : "—";

        String locationLabel = location != null ? switch (location.toLowerCase()) {
            case "malaga" -> "Málaga — C/ Alejandro Dumas 17";
            case "sevilla" -> "Sevilla — Av. de la Constitución";
            default -> location;
        } : "—";

        // Build Gmail search link for follow-up
        String gmailSearch = "https://mail.google.com/mail/u/0/#search/" +
                java.net.URLEncoder.encode("from:" + email, java.nio.charset.StandardCharsets.UTF_8);
        // WhatsApp link
        String waLink = phone != null && !phone.isBlank()
                ? "https://api.whatsapp.com/send?phone=" + phone.replaceAll("[^0-9+]", "").replace("+", "")
                : "#";

        String content = String.format("""
        <!doctype html>
        <html lang="es">
        <head>
          <meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
          <title>Nuevo registro</title>
          <style>
            @media (max-width:600px){.container{width:100%%!important}.btn-td{display:block!important;width:100%%!important;padding:0 0 10px!important}.btn-td .btn{display:inline-block!important;margin:0 auto!important}}
            .badge{display:inline-block;padding:6px 10px;border-radius:999px;background:rgba(255,255,255,0.2);color:#fff;font-weight:700;font-size:12px}
            .btn{background:#009e5c;color:#fff !important;text-decoration:none;padding:10px 14px;border-radius:10px;display:inline-block;font-weight:700;font-family:Inter,Segoe UI,Roboto,Arial,sans-serif;font-size:13px}
            .row{padding:10px 0;border-bottom:1px dashed #eee}
            .label{color:#667085;font-size:12px;letter-spacing:.3px;text-transform:uppercase;font-family:Inter,Segoe UI,Roboto,Arial,sans-serif}
            .val{font-size:16px;font-weight:700;color:#111;font-family:Inter,Segoe UI,Roboto,Arial,sans-serif}
          </style>
        </head>
        <body style="margin:0;padding:0;background:#f7f7f8;">
          <table role="presentation" width="100%%" cellspacing="0" cellpadding="0" border="0">
            <tr>
              <td align="center">
                <table class="container" role="presentation" width="620" cellspacing="0" cellpadding="0" border="0" style="width:620px;max-width:620px;margin:0 auto;">
                  <tr>
                    <td style="background:linear-gradient(135deg,#009624 0%%,#00c853 100%%);padding:22px 24px;color:#fff;border-radius:14px 14px 0 0;">
                      <div class="badge">BeWorking</div>
                      <div style="font-family:Inter,Segoe UI,Roboto,Arial,sans-serif;font-size:22px;font-weight:800;margin-top:10px;">
                        Nuevo registro%s
                      </div>
                      <div style="opacity:.9;font-family:Inter,Segoe UI,Roboto,Arial,sans-serif;font-size:14px;margin-top:4px;">
                        %s
                      </div>
                    </td>
                  </tr>
                  <tr>
                    <td style="background:#fff;padding:18px 24px;border-radius:0 0 14px 14px;border:1px solid #eee;border-top:0;">
                      <div class="row">
                        <div class="label">Nombre</div>
                        <div class="val">%s</div>
                      </div>
                      <div class="row">
                        <div class="label">Email</div>
                        <div class="val"><a href="mailto:%s" style="color:#111;text-decoration:none;">%s</a></div>
                      </div>
                      <div class="row">
                        <div class="label">Teléfono</div>
                        <div class="val"><a href="tel:%s" style="color:#111;text-decoration:none;">%s</a></div>
                      </div>
                      <div class="row">
                        <div class="label">Empresa</div>
                        <div class="val">%s</div>
                      </div>
                      <div class="row">
                        <div class="label">CIF / NIF</div>
                        <div class="val">%s</div>
                      </div>
                      <div class="row">
                        <div class="label">Plan</div>
                        <div class="val">%s</div>
                      </div>
                      <div class="row" style="border-bottom:0;">
                        <div class="label">Sede</div>
                        <div class="val">%s</div>
                      </div>

                      <div style="padding-top:16px;text-align:center;">
                        <table role="presentation" cellspacing="0" cellpadding="0" border="0" style="margin:0 auto;border-collapse:separate;border-spacing:0;">
                          <tr>
                            <td style="padding:0 6px;">
                              <a class="btn" href="%s">Ver en Gmail</a>
                            </td>
                            <td style="padding:0 6px;">
                              <a class="btn" href="tel:%s">Llamar</a>
                            </td>
                            <td style="padding:0 6px;">
                              <a class="btn" href="%s">WhatsApp</a>
                            </td>
                            <td style="padding:0 6px;">
                              <a class="btn" href="mailto:%s">Responder</a>
                            </td>
                          </tr>
                        </table>
                      </div>

                      <div style="height:14px;"></div>
                      <div style="font-family:Inter,Segoe UI,Roboto,Arial,sans-serif;font-size:12px;color:#9aa0a6;text-align:center;">
                        Tip: verifica el pago en Stripe y etiqueta como <strong>Activo</strong> en CRM.
                      </div>
                    </td>
                  </tr>
                </table>
              </td>
            </tr>
          </table>
        </body>
        </html>
        """,
        plan != null ? " — " + planLabel : " (free)",
        plan != null ? "Nuevo usuario con suscripción" : "Nuevo usuario gratuito",
        name != null ? name : "—",
        email, email,
        phone != null && !phone.isBlank() ? phone : "—",
        phone != null && !phone.isBlank() ? phone : "—",
        company != null && !company.isBlank() ? company : "—",
        taxId != null && !taxId.isBlank() ? taxId : "—",
        planLabel,
        locationLabel,
        gmailSearch,
        phone != null && !phone.isBlank() ? phone : "",
        waLink,
        email
        );

        sendHtml("info@be-working.com", "\uD83D\uDFE2 Nuevo registro — " + (name != null ? name : email), content);
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
            applyFrom(helper);
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
            applyFrom(helper);
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
