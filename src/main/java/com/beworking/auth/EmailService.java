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
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
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
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
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

    /**
     * BeKey share invite (#243): a member gave this guest door access for a
     * window. If {@code setupToken} is non-null the guest is brand new and the
     * CTA sets their (free) password; otherwise it points at login. Either way
     * the guest opens the door from inside the app — never a magic link.
     * BCC/reply-to info@ so the team can field replies.
     */
    @Async
    public void sendBeKeyShareInvite(String to, String guestName, String sharerName,
                                     java.time.OffsetDateTime startsAt, java.time.OffsetDateTime endsAt,
                                     String setupToken) {
        java.util.Locale es = new java.util.Locale("es", "ES");
        java.time.format.DateTimeFormatter fmt = java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", es);
        java.time.ZoneId madrid = java.time.ZoneId.of("Europe/Madrid");
        String startStr = startsAt.atZoneSameInstant(madrid).format(fmt);
        String endStr = endsAt.atZoneSameInstant(madrid).format(fmt);
        String safeName = (guestName != null && !guestName.isBlank()) ? guestName : "";
        String greeting = safeName.isEmpty() ? "Hola," : "Hola " + safeName + ",";

        boolean needsSetup = setupToken != null && !setupToken.isBlank();
        String ctaUrl = needsSetup ? frontendUrl + "/reset-password?token=" + setupToken : frontendUrl + "/login";
        String ctaLabel = needsSetup ? "Configurar contraseña" : "Entrar a la app";

        String subject = sharerName + " te ha dado acceso a BeWorking";
        String content = "<!doctype html>"
                + "<html lang=\"es\"><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>Acceso BeKey</title></head>"
                + "<body style=\"margin:0;padding:0;background:#f7f7f8;-webkit-font-smoothing:antialiased;\">"
                + "<table role=\"presentation\" width=\"100%\" cellspacing=\"0\" cellpadding=\"0\" border=\"0\" style=\"background:#f7f7f8;\">"
                + "<tr><td align=\"center\" style=\"padding:24px 0;\">"
                + "<table role=\"presentation\" width=\"600\" cellspacing=\"0\" cellpadding=\"0\" border=\"0\" style=\"width:600px;max-width:600px;margin:0 auto;\">"
                + "<tr><td style=\"background:linear-gradient(135deg,#009624 0%,#00c853 100%);padding:40px 32px 32px;color:#ffffff;border-radius:14px 14px 0 0;\">"
                + "<p style=\"margin:0 0 4px;font-family:Arial,Helvetica,sans-serif;font-size:13px;letter-spacing:2px;text-transform:uppercase;opacity:0.85;\">BEWORKING · BEKEY</p>"
                + "<h1 style=\"margin:0;font-family:Arial,Helvetica,sans-serif;font-size:26px;font-weight:700;line-height:1.2;color:#ffffff;\">Tienes acceso a la puerta</h1>"
                + "</td></tr>"
                + "<tr><td style=\"background:#ffffff;padding:32px;border-radius:0 0 14px 14px;border:1px solid #eee;border-top:0;\">"
                + "<p style=\"margin:0 0 8px;font-family:Arial,Helvetica,sans-serif;font-size:16px;color:#333;\">" + greeting + "</p>"
                + "<p style=\"margin:0 0 20px;font-family:Arial,Helvetica,sans-serif;font-size:14px;color:#666;line-height:1.6;\"><strong>" + sharerName + "</strong> te ha dado acceso a las puertas de BeWorking.</p>"
                + "<div style=\"margin:0 0 24px;background:#f5faf6;border-radius:10px;padding:16px 20px;border-left:4px solid #009624;\">"
                + "<p style=\"margin:0;font-family:Arial,Helvetica,sans-serif;font-size:14px;color:#333;line-height:1.6;\">Válido del <strong>" + startStr + "</strong> al <strong>" + endStr + "</strong>.</p>"
                + "</div>"
                + "<p style=\"margin:0 0 24px;font-family:Arial,Helvetica,sans-serif;font-size:14px;color:#666;line-height:1.6;\">"
                + (needsSetup
                    ? "Configura tu contraseña (cuenta gratuita) y luego abre la puerta desde la app, en la sección <strong>BeKey</strong>."
                    : "Entra en la app y abre la puerta desde la sección <strong>BeKey</strong>.")
                + "</p>"
                + "<table role=\"presentation\" cellspacing=\"0\" cellpadding=\"0\" border=\"0\" style=\"margin:0 auto;\">"
                + "<tr><td align=\"center\" style=\"border-radius:8px;background:#009624;\">"
                + "<a href=\"" + ctaUrl + "\" style=\"display:inline-block;background:#009624;color:#ffffff;text-decoration:none;font-family:Arial,Helvetica,sans-serif;font-weight:700;font-size:16px;padding:14px 36px;border-radius:8px;\">" + ctaLabel + "</a>"
                + "</td></tr></table>"
                + "<p style=\"margin:28px 0 0;font-family:Arial,Helvetica,sans-serif;font-size:13px;color:#888;text-align:center;\">"
                + "¿Necesitas ayuda? Escríbenos por WhatsApp: "
                + "<a href=\"https://wa.me/34640369759\" style=\"color:#009624;text-decoration:none;font-weight:600;\">+34 640 369 759</a></p>"
                + "<div style=\"margin:28px -32px -32px;background:#f9f9f9;padding:16px 32px;text-align:center;border-top:1px solid #eee;border-radius:0 0 14px 14px;\">"
                + "<p style=\"margin:0;font-family:Arial,Helvetica,sans-serif;font-size:12px;color:#aaa;\">© BeWorking · Málaga</p>"
                + "</div>"
                + "</td></tr></table></td></tr></table></body></html>";
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            applyFrom(helper);
            helper.setTo(to);
            helper.setBcc("info@be-working.com");
            helper.setReplyTo("info@be-working.com");
            helper.setSubject(subject);
            helper.setText(content, true);
            mailSender.send(message);
            logger.info("BeKey share invite sent to {}", to);
        } catch (Exception e) {
            logger.error("Failed to send BeKey share invite to {}: {}", to, e.getMessage(), e);
        }
    }

    /**
     * Sent right after a free user confirms their email. Explains the value of
     * the platform and nudges them toward the BeWorkingVirtual upgrade. BCCs
     * info@ so the team can pick up replies.
     */
    @Async
    public void sendFreeRegistrationWelcomeEmail(String to, String name) {
        String safeName = (name != null && !name.isBlank()) ? name : "";
        String greeting = safeName.isEmpty() ? "Hola," : "Hola " + safeName + ",";
        String dashboardUrl = frontendUrl + "/login";
        String upgradeWaLink = "https://wa.me/34640369759?text=Hola,%20me%20interesa%20BeWorkingVirtual%20por%2015%E2%82%AC/mes";
        String subject = "Bienvenido a BeWorking";
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
                + "<h1 style=\"margin:0;font-family:Arial,Helvetica,sans-serif;font-size:26px;font-weight:700;line-height:1.2;color:#ffffff;\">Bienvenido a BeWorking</h1>"
                + "</td></tr>"
                // Body
                + "<tr><td style=\"background:#ffffff;padding:32px;border-radius:0 0 14px 14px;border:1px solid #eee;border-top:0;\">"
                + "<p style=\"margin:0 0 16px;font-family:Arial,Helvetica,sans-serif;font-size:16px;color:#333;\">" + greeting + "</p>"
                + "<p style=\"margin:0 0 24px;font-family:Arial,Helvetica,sans-serif;font-size:14px;color:#555;line-height:1.6;\">"
                + "Tu cuenta ya está activa. Desde el panel puedes reservar salas de reunión y puestos de coworking, gestionar tus facturas y acceder a todas las herramientas de BeWorking.</p>"
                // Free benefits
                + "<div style=\"margin:0 0 28px;background:#f5faf6;border-radius:10px;padding:20px 24px;border-left:4px solid #009624;\">"
                + "<p style=\"margin:0 0 12px;font-family:Arial,Helvetica,sans-serif;font-size:14px;font-weight:700;color:#1a1a1a;\">Lo que tienes desde hoy</p>"
                + "<ul style=\"margin:0;padding:0 0 0 18px;font-family:Arial,Helvetica,sans-serif;font-size:14px;color:#555;line-height:1.7;\">"
                + "<li>Reserva de salas y puestos en BeWorking Málaga</li>"
                + "<li>Panel de gestión con facturas y bookings</li>"
                + "<li>Acceso a la comunidad y eventos</li>"
                + "</ul>"
                + "</div>"
                // CTA dashboard
                + "<table role=\"presentation\" cellspacing=\"0\" cellpadding=\"0\" border=\"0\" style=\"margin:0 auto 32px;\">"
                + "<tr><td align=\"center\" style=\"border-radius:8px;background:#009624;\">"
                + "<a href=\"" + dashboardUrl + "\" style=\"display:inline-block;background:#009624;color:#ffffff;text-decoration:none;font-family:Arial,Helvetica,sans-serif;font-weight:700;font-size:16px;padding:14px 36px;border-radius:8px;\">Acceder a mi cuenta</a>"
                + "</td></tr></table>"
                // Upgrade pitch
                + "<div style=\"margin:0 0 8px;border:1px solid #e8e8e8;border-radius:12px;padding:24px;\">"
                + "<p style=\"margin:0 0 6px;font-family:Arial,Helvetica,sans-serif;font-size:12px;letter-spacing:0.06em;color:#009624;text-transform:uppercase;font-weight:700;\">Sube de nivel</p>"
                + "<p style=\"margin:0 0 6px;font-family:Arial,Helvetica,sans-serif;font-size:20px;font-weight:800;color:#1a1a1a;\">BeWorking<span style=\"color:#009624;\">Virtual</span> · 15€/mes</p>"
                + "<p style=\"margin:0 0 16px;font-family:Arial,Helvetica,sans-serif;font-size:14px;color:#666;line-height:1.55;\">Domicilio fiscal y legal en Málaga, recepción de correo y paquetería, logo en recepción y 5 días de oficina al mes. Profesionaliza tu negocio sin permanencia.</p>"
                + "<ul style=\"margin:0 0 18px;padding:0 0 0 18px;font-family:Arial,Helvetica,sans-serif;font-size:13px;color:#555;line-height:1.7;\">"
                + "<li>Dirección profesional en céntrico de Málaga</li>"
                + "<li>Recepción y digitalización de correo</li>"
                + "<li>5 días de oficina al mes incluidos</li>"
                + "<li>Sin permanencia, cancela cuando quieras</li>"
                + "</ul>"
                + "<table role=\"presentation\" cellspacing=\"0\" cellpadding=\"0\" border=\"0\" style=\"margin:0;\">"
                + "<tr>"
                + "<td style=\"padding-right:8px;\">"
                + "<a href=\"" + dashboardUrl + "\" style=\"display:inline-block;background:#009624;color:#ffffff;text-decoration:none;font-family:Arial,Helvetica,sans-serif;font-weight:700;font-size:14px;padding:11px 22px;border-radius:999px;\">Activar desde el panel</a>"
                + "</td>"
                + "<td>"
                + "<a href=\"" + upgradeWaLink + "\" style=\"display:inline-block;color:#009624;text-decoration:none;font-family:Arial,Helvetica,sans-serif;font-weight:700;font-size:14px;padding:11px 18px;border:1px solid #009624;border-radius:999px;\">Hablar por WhatsApp</a>"
                + "</td>"
                + "</tr></table>"
                + "</div>"
                // Contact
                + "<p style=\"margin:24px 0 0;font-family:Arial,Helvetica,sans-serif;font-size:13px;color:#888;text-align:center;\">"
                + "¿Preguntas? Escríbenos por WhatsApp: "
                + "<a href=\"https://wa.me/34640369759\" style=\"color:#009624;text-decoration:none;font-weight:600;\">+34 640 369 759</a>"
                + " o responde a este correo.</p>"
                // Footer
                + "<div style=\"margin:28px -32px -32px;background:#f9f9f9;padding:16px 32px;text-align:center;border-top:1px solid #eee;border-radius:0 0 14px 14px;\">"
                + "<p style=\"margin:0;font-family:Arial,Helvetica,sans-serif;font-size:12px;color:#aaa;\">© BeWorking · Málaga</p>"
                + "</div>"
                + "</td></tr>"
                + "</table>"
                + "</td></tr></table>"
                + "</body></html>";
        try {
            logger.info("Attempting to send free-registration welcome email to {}", to);
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            applyFrom(helper);
            helper.setTo(to);
            helper.setBcc("info@be-working.com");
            helper.setReplyTo("info@be-working.com");
            helper.setSubject(subject);
            helper.setText(content, true);
            mailSender.send(message);
            logger.info("Free-registration welcome email sent successfully to {}", to);
        } catch (Exception e) {
            logger.error("Failed to send free-registration welcome email to {}: {}", to, e.getMessage(), e);
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
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
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
        sendSubscriptionWelcomeEmail(to, name, plan, location, null, null);
    }

    /**
     * Same as the 4-arg variant plus optional context for admin-created subs:
     * <ul>
     *   <li>{@code firstInvoiceDateIso} (e.g. "2026-06-01") — when present, the email
     *       tells the customer the first invoice will arrive that day with a Stripe
     *       payment link (card/SEPA). Pass null if no preview should be shown.</li>
     *   <li>{@code passwordSetupLink} — full URL like {@code /reset-password?token=...}
     *       embedded as a "set your password" CTA. Pass null for customers who already
     *       have credentials.</li>
     * </ul>
     */
    public void sendSubscriptionWelcomeEmail(String to, String name, String plan, String location,
                                             String firstInvoiceDateIso, String passwordSetupLink) {
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

        // First invoice block — shown only when the admin sub used send_invoice
        // collection. Formats the ISO date in Spanish (e.g. "1 de junio de 2026").
        String firstInvoiceBlock = "";
        if (firstInvoiceDateIso != null && !firstInvoiceDateIso.isBlank()) {
            String firstInvoiceLabel = firstInvoiceDateIso;
            try {
                java.time.LocalDate d = java.time.LocalDate.parse(firstInvoiceDateIso);
                java.time.format.DateTimeFormatter fmt = java.time.format.DateTimeFormatter
                    .ofPattern("d 'de' MMMM 'de' yyyy", new java.util.Locale("es"));
                firstInvoiceLabel = d.format(fmt);
            } catch (Exception ignore) {
                // fall back to ISO
            }
            firstInvoiceBlock = "<div style=\"margin:0 0 20px;background:#fff7e6;border-radius:10px;padding:16px 20px;border-left:4px solid #f5a623;\">"
                + "<p style=\"margin:0 0 6px;font-family:Arial,Helvetica,sans-serif;font-size:13px;color:#5a3a00;font-weight:600;\">Tu primera factura</p>"
                + "<p style=\"margin:0;font-family:Arial,Helvetica,sans-serif;font-size:13px;color:#5a3a00;line-height:1.6;\">El <strong>" + firstInvoiceLabel + "</strong> recibirás un correo de Stripe con el enlace para pagar tu primera mensualidad con tarjeta o SEPA. Tras ese primer pago, las siguientes mensualidades se cobrarán automáticamente.</p>"
                + "</div>";
        }

        // Password setup block — shown when the user was just created by admin and
        // hasn't set a password yet. Token expires in 7 days.
        String passwordSetupBlock = "";
        String accessHintHtml = "<p style=\"margin:0 0 12px;font-family:Arial,Helvetica,sans-serif;font-size:14px;color:#666;line-height:1.6;\">Ya puedes acceder a tu panel de control con el email y contraseña que has elegido.</p>";
        if (passwordSetupLink != null && !passwordSetupLink.isBlank()) {
            accessHintHtml = "<p style=\"margin:0 0 12px;font-family:Arial,Helvetica,sans-serif;font-size:14px;color:#666;line-height:1.6;\">Tu cuenta ya está creada. <strong>Antes de acceder, establece tu contraseña</strong> con el enlace de abajo (válido durante 7 días).</p>";
            passwordSetupBlock = "<table role=\"presentation\" cellspacing=\"0\" cellpadding=\"0\" border=\"0\" style=\"margin:0 auto 20px;\">"
                + "<tr><td align=\"center\" style=\"border-radius:8px;background:#ffffff;border:2px solid #009624;\">"
                + "<a href=\"" + passwordSetupLink + "\" style=\"display:inline-block;color:#009624;text-decoration:none;font-family:Arial,Helvetica,sans-serif;font-weight:700;font-size:15px;padding:12px 32px;\">Establecer contraseña</a>"
                + "</td></tr></table>";
        }

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
                + firstInvoiceBlock
                + accessHintHtml
                + passwordSetupBlock
                + "<p style=\"margin:0 0 28px;font-family:Arial,Helvetica,sans-serif;font-size:14px;color:#666;line-height:1.6;\">Tus facturas con todos los datos fiscales (IVA, n\u00famero PT-####) est\u00e1n disponibles en la secci\u00f3n <strong>Mis Facturas</strong> de la app. Es el documento oficial; cualquier otro recibo de pago es solo confirmaci\u00f3n.</p>"
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
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
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

    public void sendSubscriptionAdminNotification(String contactName, String contactEmail,
                                                   String description, String monthlyAmount,
                                                   String currency, String billingInterval,
                                                   String cuenta, String stripeSubscriptionId) {
        String intervalLabel = billingInterval != null ? switch (billingInterval.toLowerCase()) {
            case "month" -> "Mensual";
            case "quarter" -> "Trimestral";
            case "year" -> "Anual";
            default -> billingInterval;
        } : "Mensual";

        String cuentaLabel = cuenta != null ? switch (cuenta.toUpperCase()) {
            case "PT" -> "PT (España)";
            case "GT" -> "GT (Estonia)";
            case "BW" -> "BW";
            default -> cuenta;
        } : "—";

        String stripeLink = stripeSubscriptionId != null && !stripeSubscriptionId.isBlank()
                ? "https://dashboard.stripe.com/subscriptions/" + stripeSubscriptionId
                : "#";

        String dash = "—";
        String content = "<!doctype html>"
                + "<html lang=\"es\"><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<title>Nueva suscripción</title></head>"
                + "<body style=\"margin:0;padding:0;background:#f7f7f8;-webkit-font-smoothing:antialiased;\">"
                + "<table role=\"presentation\" width=\"100%\" cellspacing=\"0\" cellpadding=\"0\" border=\"0\">"
                + "<tr><td align=\"center\" style=\"padding:24px 0;\">"
                + "<table role=\"presentation\" width=\"600\" cellspacing=\"0\" cellpadding=\"0\" border=\"0\" style=\"width:600px;max-width:600px;margin:0 auto;\">"
                + "<tr><td style=\"background:linear-gradient(135deg,#009624 0%,#00c853 100%);padding:28px 32px;color:#ffffff;border-radius:14px 14px 0 0;\">"
                + "<p style=\"margin:0 0 4px;font-family:Arial,Helvetica,sans-serif;font-size:13px;letter-spacing:2px;text-transform:uppercase;opacity:0.85;\">BEWORKING</p>"
                + "<h1 style=\"margin:0;font-family:Arial,Helvetica,sans-serif;font-size:22px;font-weight:700;line-height:1.2;color:#ffffff;\">Nueva suscripción creada</h1>"
                + "</td></tr>"
                + "<tr><td style=\"background:#ffffff;padding:28px 32px;border-radius:0 0 14px 14px;border:1px solid #eee;border-top:0;\">"
                + "<p style=\"margin:0 0 18px;font-family:Arial,Helvetica,sans-serif;font-size:14px;color:#555;\">Se ha registrado una nueva suscripción desde el panel de admin. Stripe enviará la primera factura al cliente con las opciones de pago (tarjeta y SEPA).</p>"
                + "<div style=\"padding:8px 0;border-bottom:1px dashed #eee;\"><div style=\"font-family:Arial,Helvetica,sans-serif;font-size:12px;color:#667085;text-transform:uppercase;letter-spacing:.3px;\">Cliente</div><div style=\"font-family:Arial,Helvetica,sans-serif;font-size:16px;font-weight:700;color:#111;\">" + (contactName != null ? contactName : dash) + "</div></div>"
                + "<div style=\"padding:8px 0;border-bottom:1px dashed #eee;\"><div style=\"font-family:Arial,Helvetica,sans-serif;font-size:12px;color:#667085;text-transform:uppercase;letter-spacing:.3px;\">Email</div><div style=\"font-family:Arial,Helvetica,sans-serif;font-size:16px;font-weight:700;color:#111;\"><a href=\"mailto:" + (contactEmail != null ? contactEmail : "") + "\" style=\"color:#111;text-decoration:none;\">" + (contactEmail != null ? contactEmail : dash) + "</a></div></div>"
                + "<div style=\"padding:8px 0;border-bottom:1px dashed #eee;\"><div style=\"font-family:Arial,Helvetica,sans-serif;font-size:12px;color:#667085;text-transform:uppercase;letter-spacing:.3px;\">Concepto</div><div style=\"font-family:Arial,Helvetica,sans-serif;font-size:16px;font-weight:700;color:#111;\">" + (description != null ? description : dash) + "</div></div>"
                + "<div style=\"padding:8px 0;border-bottom:1px dashed #eee;\"><div style=\"font-family:Arial,Helvetica,sans-serif;font-size:12px;color:#667085;text-transform:uppercase;letter-spacing:.3px;\">Importe</div><div style=\"font-family:Arial,Helvetica,sans-serif;font-size:16px;font-weight:700;color:#111;\">" + (monthlyAmount != null ? monthlyAmount : dash) + " " + (currency != null ? currency.toUpperCase() : "EUR") + " · " + intervalLabel + "</div></div>"
                + "<div style=\"padding:8px 0;border-bottom:1px dashed #eee;\"><div style=\"font-family:Arial,Helvetica,sans-serif;font-size:12px;color:#667085;text-transform:uppercase;letter-spacing:.3px;\">Cuenta</div><div style=\"font-family:Arial,Helvetica,sans-serif;font-size:16px;font-weight:700;color:#111;\">" + cuentaLabel + "</div></div>"
                + "<div style=\"padding:8px 0;\"><div style=\"font-family:Arial,Helvetica,sans-serif;font-size:12px;color:#667085;text-transform:uppercase;letter-spacing:.3px;\">Stripe ID</div><div style=\"font-family:Arial,Helvetica,sans-serif;font-size:13px;color:#111;word-break:break-all;\">" + (stripeSubscriptionId != null && !stripeSubscriptionId.isBlank() ? stripeSubscriptionId : dash) + "</div></div>"
                + "<div style=\"margin-top:18px;text-align:center;\">"
                + "<a href=\"" + stripeLink + "\" style=\"display:inline-block;background:#009624;color:#ffffff;text-decoration:none;font-family:Arial,Helvetica,sans-serif;font-weight:700;font-size:14px;padding:12px 24px;border-radius:8px;\">Ver en Stripe</a>"
                + "</div>"
                + "</td></tr>"
                + "</table>"
                + "</td></tr></table>"
                + "</body></html>";

        sendHtml("info@be-working.com",
                 "🟢 Nueva suscripción — " + (contactName != null ? contactName : contactEmail),
                 content);
    }

    public void sendSubscriptionCancellationAdminNotification(String contactName, String contactEmail,
                                                              String description, String cuenta,
                                                              String stripeSubscriptionId, Integer localSubId,
                                                              String cancelledBy) {
        String dash = "—";
        String stripeLink = stripeSubscriptionId != null && !stripeSubscriptionId.isBlank()
                ? "https://dashboard.stripe.com/subscriptions/" + stripeSubscriptionId
                : null;

        String body = "<!doctype html><html lang=\"es\"><head><meta charset=\"utf-8\"></head>"
                + "<body style=\"margin:0;padding:0;background:#f7f7f8;font-family:Arial,Helvetica,sans-serif;\">"
                + "<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\"><tr><td align=\"center\" style=\"padding:24px 0;\">"
                + "<table role=\"presentation\" width=\"600\" style=\"max-width:600px;\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\">"
                + "<tr><td style=\"background:#b71c1c;color:#fff;padding:24px 28px;border-radius:14px 14px 0 0;\">"
                + "<p style=\"margin:0 0 4px;font-size:13px;letter-spacing:2px;text-transform:uppercase;opacity:.85;\">BEWORKING</p>"
                + "<h1 style=\"margin:0;font-size:22px;font-weight:700;\">Suscripción cancelada</h1></td></tr>"
                + "<tr><td style=\"background:#fff;padding:24px 28px;border:1px solid #eee;border-top:0;border-radius:0 0 14px 14px;\">"
                + "<p style=\"margin:0 0 18px;color:#555;font-size:14px;\">Una suscripción acaba de ser cancelada manualmente.</p>"
                + cancelRow("Cliente", contactName != null ? contactName : dash)
                + cancelRow("Email", contactEmail != null ? contactEmail : dash)
                + cancelRow("Concepto", description != null ? description : dash)
                + cancelRow("Cuenta", cuenta != null ? cuenta : dash)
                + cancelRow("Cancelado por", cancelledBy != null ? cancelledBy : dash)
                + cancelRow("Sub local", localSubId != null ? String.valueOf(localSubId) : dash)
                + cancelRow("Stripe ID", stripeSubscriptionId != null && !stripeSubscriptionId.isBlank() ? stripeSubscriptionId : dash)
                + (stripeLink != null
                    ? "<div style=\"margin-top:18px;text-align:center;\"><a href=\"" + stripeLink + "\" style=\"display:inline-block;background:#b71c1c;color:#fff;text-decoration:none;font-weight:700;font-size:14px;padding:12px 24px;border-radius:8px;\">Ver en Stripe</a></div>"
                    : "")
                + "</td></tr></table></td></tr></table></body></html>";

        String subject = "🔴 Suscripción cancelada — " + (contactName != null ? contactName : (contactEmail != null ? contactEmail : "—"));
        sendHtml("info@be-working.com", subject, body);
    }

    private static String cancelRow(String label, String value) {
        return "<div style=\"padding:8px 0;border-bottom:1px dashed #eee;\">"
                + "<div style=\"font-size:12px;color:#667085;text-transform:uppercase;letter-spacing:.3px;\">" + label + "</div>"
                + "<div style=\"font-size:16px;font-weight:700;color:#111;\">" + value + "</div></div>";
    }

    @Async
    public void sendHtml(String to, String subject, String htmlContent) {
        sendHtml(to, subject, htmlContent, null);
    }

    @Async
    public void sendHtml(String to, String subject, String htmlContent, String replyTo) {
        sendHtmlInternal(to, subject, htmlContent, replyTo, false);
    }

    /**
     * Cron-style email to a customer that BCCs info@be-working.com and sets
     * Reply-To info@ so replies land in the team inbox. Used by dunning /
     * past-due reminders where a human follow-up is expected.
     */
    @Async
    public void sendBccInfo(String to, String subject, String htmlContent) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            applyFrom(helper);
            helper.setTo(to);
            helper.setBcc("info@be-working.com");
            helper.setReplyTo("info@be-working.com");
            helper.setSubject(subject);
            helper.setText(htmlContent, true);
            mailSender.send(message);
            logger.info("Customer email (BCC info@) sent to {}", to);
        } catch (Exception e) {
            logger.error("Failed to send BCC-info email to {}: {}", to, e.getMessage(), e);
        }
    }

    /**
     * Send abandonment-recovery email. BCCs info@be-working.com so the team
     * can track who's been contacted. Best-effort; logs failures.
     */
    /**
     * Send recovery email N to a contact who's still at status='Potencial'.
     * The 4-touch sequence is owned by AbandonmentRecoveryScheduler; this
     * method just dispatches the matching template. info@be-working.com is
     * BCC'd so the team can pick up replies.
     */
    @Async
    public void sendRecoveryEmail(String to, String name, int templateNumber) {
        sendRecoveryEmail(to, name, templateNumber, null);
    }

    /**
     * Same as the 3-arg variant plus a contactId used to inject an
     * open-tracking pixel and tag links with UTM params. Skip the contactId
     * (or pass null) for ad-hoc sends without tracking.
     */
    @Async
    public void sendRecoveryEmail(String to, String name, int templateNumber, Long contactId) {
        String safeName = (name != null && !name.isBlank()) ? name : "";
        String greeting = safeName.isEmpty() ? "Hola," : "Hola " + safeName + ",";
        RecoveryTemplate tpl = recoveryTemplate(templateNumber, greeting);
        String body = tpl.body();
        if (contactId != null) {
            body = injectTracking(body, "recovery", templateNumber, contactId);
        }
        String html = recoveryEmailShell(tpl.headline(), body);
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            applyFrom(helper);
            helper.setTo(to);
            helper.setBcc("info@be-working.com");
            helper.setReplyTo("info@be-working.com");
            helper.setSubject(tpl.subject());
            helper.setText(html, true);
            mailSender.send(message);
            logger.info("Recovery email #{} sent to {}", templateNumber, to);
        } catch (Exception e) {
            logger.error("Failed to send recovery email #{} to {}: {}", templateNumber, to, e.getMessage(), e);
        }
    }

    /**
     * 4-touch nurture sequence for leads in 'Contactado'. Mirrors the
     * Potencial recovery cadence (T+30min, T+1d, T+3d, T+6d) but with
     * inquiry-style copy — leads asked a question, they didn't try to pay.
     * Owned by LeadNurtureScheduler. info@ is BCC'd so replies thread back.
     */
    @Async
    public void sendLeadNurtureEmail(String to, String name, int templateNumber) {
        String safeName = (name != null && !name.isBlank()) ? name : "";
        String greeting = safeName.isEmpty() ? "Hola," : "Hola " + safeName + ",";
        RecoveryTemplate tpl = leadNurtureTemplate(templateNumber, greeting);
        String html = recoveryEmailShell(tpl.headline(), tpl.body());
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            applyFrom(helper);
            helper.setTo(to);
            helper.setBcc("info@be-working.com");
            helper.setReplyTo("info@be-working.com");
            helper.setSubject(tpl.subject());
            helper.setText(html, true);
            mailSender.send(message);
            logger.info("Lead nurture email #{} sent to {}", templateNumber, to);
        } catch (Exception e) {
            logger.error("Failed to send lead nurture email #{} to {}: {}", templateNumber, to, e.getMessage(), e);
        }
    }

    private RecoveryTemplate leadNurtureTemplate(int n, String greeting) {
        String waLink = "https://wa.me/34640369759?text=Hola,%20tengo%20una%20pregunta%20sobre%20BeWorking";
        String waButton = "<table role=\"presentation\" cellspacing=\"0\" cellpadding=\"0\" border=\"0\" style=\"margin:0 auto 16px;\"><tr>"
            + "<td style=\"background:#009624;border-radius:999px;\">"
            + "<a href=\"" + waLink + "\" style=\"display:inline-block;padding:12px 28px;color:#fff;text-decoration:none;font-weight:600;font-size:15px;\">Hablar por WhatsApp</a>"
            + "</td></tr></table>";
        return switch (n) {
            case 2 -> new RecoveryTemplate(
                "¿Has podido revisar la información? — BeWorking",
                "¿Pudiste revisar lo que te enviamos?",
                "<p style=\"margin:0 0 16px;\">" + greeting + "</p>"
                + "<p style=\"margin:0 0 16px;\">Ayer te contactamos tras tu mensaje. Queríamos comprobar si has tenido tiempo de revisar la información y si te queda alguna duda.</p>"
                + "<p style=\"margin:0 0 24px;\">Si prefieres una respuesta rápida, escríbenos por WhatsApp — el equipo te atiende en directo.</p>"
                + waButton
                + "<p style=\"margin:0;color:#666;font-size:13px;text-align:center;\">o responde a este correo si lo prefieres.</p>"
            );
            case 3 -> new RecoveryTemplate(
                "Algunas preguntas frecuentes — BeWorking",
                "Quizá esto te ayude",
                "<p style=\"margin:0 0 16px;\">" + greeting + "</p>"
                + "<p style=\"margin:0 0 16px;\">Pasados unos días, te dejamos algunas respuestas a lo que más nos preguntan:</p>"
                + "<p style=\"margin:0 0 8px;\"><strong>¿Cuánto cuesta?</strong> Tenemos planes desde 15€/mes para oficina virtual.</p>"
                + "<p style=\"margin:0 0 8px;\"><strong>¿Puedo darme de alta hoy?</strong> Sí, el proceso es 100% online y tarda menos de 5 minutos.</p>"
                + "<p style=\"margin:0 0 24px;\"><strong>¿Puedo cancelar cuando quiera?</strong> Sí, sin permanencia.</p>"
                + "<p style=\"margin:0 0 24px;\">Si tienes una pregunta distinta, cuéntanosla — estamos para resolverla.</p>"
                + waButton
            );
            case 4 -> new RecoveryTemplate(
                "Último mensaje — ¿podemos ayudarte? — BeWorking",
                "Última llamada",
                "<p style=\"margin:0 0 16px;\">" + greeting + "</p>"
                + "<p style=\"margin:0 0 16px;\">Esta es la última vez que te escribimos sobre tu consulta. No queremos saturarte.</p>"
                + "<p style=\"margin:0 0 24px;\">Si en algún momento quieres retomar la conversación, aquí estaremos. Y si la consulta se resolvió por otro lado, gracias por considerarnos.</p>"
                + waButton
                + "<p style=\"margin:0;color:#666;font-size:13px;text-align:center;\">Un saludo del equipo BeWorking.</p>"
            );
            default -> new RecoveryTemplate(
                "¿Te puedo ayudar con algo más? — BeWorking",
                "¿Te puedo ayudar con algo más?",
                "<p style=\"margin:0 0 16px;\">" + greeting + "</p>"
                + "<p style=\"margin:0 0 16px;\">Recibimos tu mensaje hace un rato. Queríamos asegurarnos de que te llegó nuestra respuesta y comprobar si tienes alguna duda adicional.</p>"
                + "<p style=\"margin:0 0 24px;\">Si te resulta más fácil, podemos hablar por WhatsApp — un compañero del equipo te resuelve dudas en directo.</p>"
                + waButton
                + "<p style=\"margin:0;color:#666;font-size:13px;text-align:center;\">o responde a este correo y te contestamos.</p>"
            );
        };
    }

    /**
     * Soft re-engagement email for contacts at status='Inactivo'. Sent on a
     * 6-month cadence by InactivoReengagementScheduler, max 3 times. Tone is
     * "long time no see" — no urgency, no discount, just a reminder we're
     * still here. info@be-working.com is BCC'd so a reply opens a thread.
     */
    @Async
    public void sendReengagementEmail(String to, String name) {
        sendReengagementEmail(to, name, null, 1);
    }

    /**
     * Same as the 2-arg variant plus a contactId + the email number
     * (1, 2, or 3) used to inject an open-tracking pixel. Pass null for
     * the contactId for sends without tracking.
     */
    @Async
    public void sendReengagementEmail(String to, String name, Long contactId, int emailNumber) {
        String safeName = (name != null && !name.isBlank()) ? name : "";
        String greeting = safeName.isEmpty() ? "Hola," : "Hola " + safeName + ",";
        String waLink = "https://wa.me/34640369759?text=Hola,%20me%20gustaria%20saber%20que%20espacios%20teneis";
        String waButton = "<table role=\"presentation\" cellspacing=\"0\" cellpadding=\"0\" border=\"0\" style=\"margin:0 auto 16px;\"><tr>"
            + "<td style=\"background:#009624;border-radius:999px;\">"
            + "<a href=\"" + waLink + "\" style=\"display:inline-block;padding:12px 28px;color:#fff;text-decoration:none;font-weight:600;font-size:15px;\">Hablar por WhatsApp</a>"
            + "</td></tr></table>";
        String body = "<p style=\"margin:0 0 16px;\">" + greeting + "</p>"
            + "<p style=\"margin:0 0 16px;\">Hace tiempo que no nos vemos. ¿Cómo va todo?</p>"
            + "<p style=\"margin:0 0 16px;\">En BeWorking seguimos aquí — oficinas virtuales, salas de reunión, espacios de trabajo. Si tu negocio necesita una dirección fiscal, una sala para reunirte con un cliente o un puesto en coworking, lo tenemos preparado.</p>"
            + "<p style=\"margin:0 0 24px;\">Sin compromiso. Si te interesa lo que ofrecemos hoy, escríbenos y te lo contamos.</p>"
            + waButton
            + "<p style=\"margin:0;color:#666;font-size:13px;text-align:center;\">o responde a este correo y te contestamos.</p>";
        if (contactId != null) {
            body = injectTracking(body, "reengagement", emailNumber, contactId);
        }
        String html = recoveryEmailShell("¿Cuánto tiempo!", body);
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            applyFrom(helper);
            helper.setTo(to);
            // No BCC to info@ — reengagement is a 1:1 customer touch. The cron
            // sends a single run-summary to info@ instead (sendReengagementCronSummary).
            helper.setReplyTo("info@be-working.com");
            helper.setSubject("¿Cuánto tiempo! ¿Volvemos a vernos? — BeWorking");
            helper.setText(html, true);
            mailSender.send(message);
            logger.info("Reengagement email sent to {}", to);
        } catch (Exception e) {
            logger.error("Failed to send reengagement email to {}: {}", to, e.getMessage(), e);
        }
    }

    /**
     * Single internal notice to info@ confirming the reengagement cron ran.
     * Replaces the per-email BCC: the cron fires unattended, so the team gets
     * one summary (counts) instead of a copy of every customer email.
     */
    public void sendReengagementCronSummary(int sent, int skipped, int notDue, int totalCandidates) {
        String body = "<p style=\"margin:0 0 16px;\">El cron de reengagement (Inactivo) se ha ejecutado.</p>"
            + "<table role=\"presentation\" cellspacing=\"0\" cellpadding=\"0\" border=\"0\" style=\"margin:0 0 16px;font-size:15px;color:#1a1a1a;\">"
            + "<tr><td style=\"padding:4px 16px 4px 0;\">Emails enviados</td><td style=\"font-weight:600;\">" + sent + "</td></tr>"
            + "<tr><td style=\"padding:4px 16px 4px 0;\">Saltados (sin email)</td><td>" + skipped + "</td></tr>"
            + "<tr><td style=\"padding:4px 16px 4px 0;\">No tocaban aún</td><td>" + notDue + "</td></tr>"
            + "<tr><td style=\"padding:4px 16px 4px 0;\">Candidatos Inactivo</td><td>" + totalCandidates + "</td></tr>"
            + "</table>"
            + "<p style=\"margin:0;color:#666;font-size:13px;\">Aviso automático interno. No requiere acción.</p>";
        String html = recoveryEmailShell("Cron reengagement ejecutado", body);
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            applyFrom(helper);
            helper.setTo("info@be-working.com");
            helper.setReplyTo("info@be-working.com");
            helper.setSubject("Cron reengagement ejecutado — " + sent + " enviados");
            helper.setText(html, true);
            mailSender.send(message);
            logger.info("Reengagement cron summary sent to info@ (sent={})", sent);
        } catch (Exception e) {
            logger.error("Failed to send reengagement cron summary: {}", e.getMessage(), e);
        }
    }

    private record RecoveryTemplate(String subject, String headline, String body) {}

    private RecoveryTemplate recoveryTemplate(int n, String greeting) {
        String waLink = "https://wa.me/34640369759?text=Hola,%20necesito%20ayuda%20para%20completar%20mi%20registro";
        String waButton = "<table role=\"presentation\" cellspacing=\"0\" cellpadding=\"0\" border=\"0\" style=\"margin:0 auto 16px;\"><tr>"
            + "<td style=\"background:#009624;border-radius:999px;\">"
            + "<a href=\"" + waLink + "\" style=\"display:inline-block;padding:12px 28px;color:#fff;text-decoration:none;font-weight:600;font-size:15px;\">Hablar por WhatsApp</a>"
            + "</td></tr></table>";
        return switch (n) {
            case 2 -> new RecoveryTemplate(
                "Te ayudamos a terminar tu reserva — BeWorking",
                "Estamos aquí para ayudarte",
                "<p style=\"margin:0 0 16px;\">" + greeting + "</p>"
                + "<p style=\"margin:0 0 16px;\">Hace un día empezaste el proceso pero no llegamos a completarlo juntos. ¿Necesitas que te ayudemos en algún paso?</p>"
                + "<p style=\"margin:0 0 24px;\">Si prefieres hablar antes de pagar, escríbenos por WhatsApp y un compañero del equipo te resuelve dudas en directo. Es lo más rápido.</p>"
                + waButton
                + "<p style=\"margin:0;color:#666;font-size:13px;text-align:center;\">o responde a este correo si lo prefieres.</p>"
            );
            case 3 -> new RecoveryTemplate(
                "Otros eligen BeWorking porque… — BeWorking",
                "¿Qué dicen quienes ya están dentro?",
                "<p style=\"margin:0 0 16px;\">" + greeting + "</p>"
                + "<p style=\"margin:0 0 16px;\">Han pasado unos días y queríamos volver a contactarte. La mayoría de las personas que dan el paso con nosotros nos cuentan lo mismo:</p>"
                + "<p style=\"margin:0 0 8px;color:#1a1a1a;\"><em>«Lo que más valoro es responder rápido a clientes y proveedores con una dirección fiscal y atención telefónica reales.»</em></p>"
                + "<p style=\"margin:0 0 24px;color:#666;font-size:13px;\">— María, autónoma desde 2024</p>"
                + "<p style=\"margin:0 0 24px;\">Si estás dudando, cuéntanos qué te frena y vemos cómo encajarlo. Sin compromiso.</p>"
                + waButton
            );
            case 4 -> new RecoveryTemplate(
                "Última oportunidad — ¿podemos ayudarte? — BeWorking",
                "Última llamada",
                "<p style=\"margin:0 0 16px;\">" + greeting + "</p>"
                + "<p style=\"margin:0 0 16px;\">Esta es la última vez que te escribimos sobre tu registro pendiente. No queremos saturarte.</p>"
                + "<p style=\"margin:0 0 24px;\">Si en algún momento quieres retomarlo, aquí estaremos. Y si simplemente no era el momento, gracias por considerarnos.</p>"
                + waButton
                + "<p style=\"margin:0;color:#666;font-size:13px;text-align:center;\">Un saludo del equipo BeWorking.</p>"
            );
            default -> new RecoveryTemplate(
                "¿Necesitas ayuda para terminar tu registro? — BeWorking",
                "¿Necesitas ayuda para terminar?",
                "<p style=\"margin:0 0 16px;\">" + greeting + "</p>"
                + "<p style=\"margin:0 0 16px;\">Vimos que empezaste el registro en BeWorking pero no llegaste a completar el pago. Pasa muchas veces — formulario, banco, una duda — y queríamos echarte una mano.</p>"
                + "<p style=\"margin:0 0 24px;\">¿Qué te frenó? Responde a este correo o escríbenos por WhatsApp y resolvemos cualquier duda en menos de un día hábil. Sin compromiso.</p>"
                + waButton
                + "<p style=\"margin:0;color:#666;font-size:13px;text-align:center;\">o responde a este correo y te contestamos.</p>"
            );
        };
    }

    private String recoveryEmailShell(String headline, String body) {
        return "<!doctype html>"
            + "<html lang=\"es\"><head><meta charset=\"utf-8\">"
            + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"></head>"
            + "<body style=\"margin:0;padding:0;background:#f7f7f8;-webkit-font-smoothing:antialiased;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif;\">"
            + "<table role=\"presentation\" width=\"100%\" cellspacing=\"0\" cellpadding=\"0\" border=\"0\" style=\"background:#f7f7f8;\">"
            + "<tr><td align=\"center\" style=\"padding:32px 0;\">"
            + "<table role=\"presentation\" width=\"560\" cellspacing=\"0\" cellpadding=\"0\" border=\"0\" style=\"width:560px;max-width:560px;margin:0 auto;\">"
            + "<tr><td style=\"background:linear-gradient(135deg,#009624 0%,#00c853 100%);padding:32px;color:#fff;border-radius:14px 14px 0 0;\">"
            + "<div style=\"font-size:14px;letter-spacing:0.06em;text-transform:uppercase;opacity:.85;\">BeWorking</div>"
            + "<div style=\"font-size:24px;font-weight:700;margin-top:8px;\">" + headline + "</div>"
            + "</td></tr>"
            + "<tr><td style=\"background:#fff;padding:32px;color:#1a1a1a;line-height:1.55;\">"
            + body
            + "</td></tr>"
            + "<tr><td style=\"background:#fafafa;padding:20px 32px;color:#888;font-size:12px;line-height:1.5;border-radius:0 0 14px 14px;\">"
            + "BeWorking · Calle Alejandro Dumas 17, 29004 Málaga · <a href=\"mailto:info@be-working.com\" style=\"color:#009624;text-decoration:none;\">info@be-working.com</a>"
            + "</td></tr>"
            + "</table></td></tr></table></body></html>";
    }

    /**
     * Append a 1×1 transparent tracking pixel that hits
     * {@code /api/track/open?c=…&t=…&type=…} when the recipient opens the
     * email. Inline images get auto-loaded by most clients (with caching),
     * which is why the controller sets no-cache headers — Gmail proxies
     * still cache, but we get a single signal which is enough for first-open
     * rate.
     */
    private String injectTracking(String body, String type, int templateNumber, Long contactId) {
        String pixelUrl = (baseUrl != null ? baseUrl.replaceAll("/+$", "") : "")
            + "/api/track/open?c=" + contactId
            + "&t=" + templateNumber
            + "&type=" + type;
        return body
            + "<img src=\"" + pixelUrl + "\" alt=\"\" width=\"1\" height=\"1\""
            + " style=\"display:block;width:1px;height:1px;border:0;outline:none;text-decoration:none\" />";
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
