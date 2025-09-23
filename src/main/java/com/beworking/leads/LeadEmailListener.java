package com.beworking.leads;

import com.beworking.auth.EmailService;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;


@Component
public class LeadEmailListener {
        private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(LeadEmailListener.class);
        private final EmailService emailService;

        public LeadEmailListener (EmailService emailService) {
                this.emailService = emailService;
        }
        
        // This method listens for LeadCreatedEvent and sends emails after the transaction is committed
       @TransactionalEventListener(phase = org.springframework.transaction.event.TransactionPhase.AFTER_COMMIT)
       public void handleLeadCreated(LeadCreatedEvent event) {
            logger.info("Checkpoint 1: LeadEmailListener triggered for event: {}", event);
            Lead lead = event.getLead(); // Get the lead from the event
            // User email
            String userSubject = "¡Gracias! Tu Oficina Virtual ya está en marcha 🚀";
            String userHtml = LeadEmailService.getUserHtml(lead.getName());
            String userMessageId = emailService.sendHtmlAndReturnMessageId(lead.getEmail(), userSubject, userHtml);
            // Admin notification email
            String teamSubject = "🟧 Nuevo lead — BeWorking";
            String waLink = buildWhatsappLink(lead.getPhone());
            String waWebLink = buildWhatsappWebLink(waLink);
            String gmailThreadLink = buildGmailThreadLink(userMessageId);
            String mailtoReplyLink = buildMailtoReplyLink(lead.getEmail());
            String teamHtml = LeadEmailService.getAdminHtml(
                lead.getName(),
                lead.getEmail(),
                lead.getPhone(),
                gmailThreadLink,
                mailtoReplyLink,
                waLink,
                waWebLink
            );
            emailService.sendHtml("info@be-working.com", teamSubject, teamHtml, lead.getEmail());
       }

        

        static String buildWhatsappLink(String rawPhone) {
            final String baseUrl = "https://api.whatsapp.com/send/?phone=";
            final String suffix = "&text=&type=phone_number&app_absent=0";

            if (rawPhone == null || rawPhone.trim().isEmpty()) {
                return baseUrl + suffix;
            }

            String digits = rawPhone.replaceAll("\\D+", "");

            if (digits.isEmpty()) {
                return baseUrl + suffix;
            }

            if (digits.startsWith("00")) {
                digits = digits.substring(2);
            }

            digits = digits.replaceFirst("^0+", "");

            if (!digits.startsWith("34") && digits.length() == 9) {
                digits = "34" + digits;
            }

            return baseUrl + digits + suffix;
        }

        static String buildWhatsappWebLink(String apiLink) {
            if (apiLink == null || apiLink.isEmpty()) {
                return "https://web.whatsapp.com/";
            }

            String webLink = apiLink
                .replace("api.whatsapp.com/send/?phone=", "web.whatsapp.com/send?phone=")
                .replace("&type=phone_number&app_absent=0", "");

            if (webLink.endsWith("&text=")) {
                webLink = webLink.substring(0, webLink.length() - 6);
            }

            return webLink;
        }

        static String buildMailtoReplyLink(String to) {
            if (to == null || to.trim().isEmpty()) {
                return "mailto:";
            }

            String subject = "Re: ¡Gracias! Tu Oficina Virtual ya está en marcha 🚀";
            String encodedSubject = URLEncoder.encode(subject, StandardCharsets.UTF_8).replace("+", "%20");
            return "mailto:" + to + "?subject=" + encodedSubject;
        }

        static String buildGmailThreadLink(String messageId) {
            if (messageId == null || messageId.isBlank()) {
                return "https://mail.google.com/mail/";
            }

            String query = "rfc822msgid:" + messageId;
            String encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8).replace("+", "%20");
            return "https://mail.google.com/mail/u/0/#search/" + encodedQuery;
        }
}
