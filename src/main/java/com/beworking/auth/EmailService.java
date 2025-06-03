package com.beworking.auth;

import org.springframework.mail.javamail.JavaMailSender; // 
import org.springframework.mail.javamail.MimeMessageHelper;
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

}
