package com.beworking.auth;

import org.springframework.mail.javamail.JavaMailSender; // 
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class EmailService {
    // This field is used to send emails via Spring's JavaMailSender
    private final JavaMailSender mailSender;
    private static final Logger logger = LoggerFactory.getLogger(EmailService.class);

    public EmailService(JavaMailSender mailSender) {
        this.mailSender = mailSender;
    }
    public void sendConfirmationEmail(String to, String token) {
        String subject = "Confirm your email address";
        String confirmation = "http://localhost:8080/api/auth/confirm?token=" + token;
        String content = "<p>Thank you for registering!</p>"
                + "<p>Please confirm your email address by clicking the link below:</p>"
                + "<a href=\"" + confirmation + "\">Confirm Email</a>";
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
        String subject = "Reset your password";
        String resetLink = "http://localhost:3020/main/reset-password?token=" + token;
        String content = "<p>You requested a password reset.</p>"
                + "<p>Click the link below to reset your password. This link will expire in 1 hour.</p>"
                + "<a href='" + resetLink + "'>Reset Password</a>";
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

    private String sendHtmlInternal(String to, String subject, String htmlContent, String replyTo, boolean returnMessageId) {
        try {
            logger.info("Attempting to send HTML email to {}", to);
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(htmlContent, true); // true = HTML
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
