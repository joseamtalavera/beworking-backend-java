package com.beworking.auth;

import org.springframework.mail.javamail.JavaMailSender; // 
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;

@Service
public class EmailService {
    // This field is used to send emails via Spring's JavaMailSender
    private final JavaMailSender mailSender;

    public EmailService(JavaMailSender mailSender) {
        this.mailSender = mailSender;
    }
    public void sendConfirmationEmail(String to, String token) throws MessagingException {
        String subject = "Confirm your email address";
        String confirmation = "http://localhost:2030/confirm?token=" + token;
        String content = "<p>Thank you for registering!</p>"
                + "<p>Please confirm your email address by clicking the link below:</p>"
                + "<a href=\"" + confirmation + "\">Confirm Email</a>";
        
        MimeMessage message = mailSender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(message, true);
        helper.setTo(to);
        helper.setSubject(subject);
        helper.setText(content, true);
        mailSender.send(message);
    }

}
