package com.beworking.leads;

import com.beworking.contacts.ContactProfileCreatedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Lead → customer conversion cleanup.
 *
 * <p>When a new ContactProfile is created, the person is no longer a "lead"
 * in our funnel — they're a customer. Hard-delete any matching lead row (by
 * email) so the leads dashboard stays a true pipeline view and HubSpot doesn't
 * keep nagging about a "lead" we've already converted.
 *
 * <p>Runs AFTER_COMMIT so the lead is only purged once the profile creation
 * actually persisted; if profile creation rolls back, the lead stays.
 */
@Component
public class LeadCleanupListener {

    private static final Logger log = LoggerFactory.getLogger(LeadCleanupListener.class);

    private final LeadRepository leadRepository;

    public LeadCleanupListener(LeadRepository leadRepository) {
        this.leadRepository = leadRepository;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onContactProfileCreated(ContactProfileCreatedEvent event) {
        String email = event.email();
        if (email == null || email.isBlank()) return;
        long deleted = leadRepository.deleteByEmailIgnoreCase(email.trim());
        if (deleted > 0) {
            log.info("Lead → customer conversion: deleted {} lead row(s) for email={}, contactProfileId={}",
                deleted, email, event.contactProfileId());
        }
    }
}
