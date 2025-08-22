package com.beworking.integrations.hubspot;

import com.beworking.leads.LeadCreatedEvent;
import com.beworking.leads.Lead;
import com.beworking.leads.LeadRepository;
import com.beworking.leads.SyncStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import java.time.Instant;

@Component
public class LeadHubspotSyncListener {
    private static final Logger log = LoggerFactory.getLogger(LeadHubspotSyncListener.class);
    private final HubspotService hubspotService;
    private final LeadRepository leadRepository;

    public LeadHubspotSyncListener(HubspotService hubspotService, LeadRepository leadRepository){
        this.hubspotService = hubspotService;
        this.leadRepository = leadRepository;
    }

    @TransactionalEventListener(phase = org.springframework.transaction.event.TransactionPhase.AFTER_COMMIT)
    @Async // This method will run asynchronously after the transaction is committed. It calls the
    public void onLeadCreated(LeadCreatedEvent event){
        Lead lead = event.getLead();
        log.info("LeadHubspotSyncListener triggered for lead id={}", lead.getId());
        try {
            HubspotService.HubspotSyncResult result = hubspotService.syncLead(lead);
            
            if (result.isSuccess()) {
                lead.setHubspotSyncStatus(SyncStatus.SYNCED);
                lead.setHubspotId(result.getId());
                lead.setHubspotError(null);
            } else{
                lead.setHubspotSyncStatus(SyncStatus.FAILED);
                lead.setHubspotError(result.getError());
            }

            // Update the lead in the database, counters and timestamps
            lead.setHubspotSyncAttempts(
                lead.getHubspotSyncAttempts() == null ? 1 : lead.getHubspotSyncAttempts() + 1
            );
            lead.setHubspotAttemptAt(Instant.now());

            // Save the lead with updated HubSpot sync information
            leadRepository.save(lead);
        } catch (Exception e) {
            log.error("Error syncing lead {} to HubSpot", lead.getId(), e.getMessage(), e);
        }
    }
}