package com.beworking.leads;

import com.beworking.auth.EmailService;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;


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
            emailService.sendHtml(lead.getEmail(), userSubject, userHtml);
            // Admin notification email
            String teamSubject = "🟧 Nuevo lead — BeWorking";
            String phoneDigits = lead.getPhone() == null ? "" : lead.getPhone().replaceAll("\\D+","");
            String waLink = phoneDigits.isEmpty() ? "https://wa.me/" : ("https://wa.me/" + phoneDigits);
            String teamHtml = LeadEmailService.getAdminHtml(
                lead.getName(),
                lead.getEmail(),
                lead.getPhone(),
                waLink
            );
            emailService.sendHtml("info@be-working.com", teamSubject, teamHtml);
       }

        
}