package com.beworking.integrations.hubspot;

import com.beworking.leads.LeadCreatedEvent;
import com.beworking.leads.Lead;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class LeadHubspotSyncListener {
    private static final Logger log = LoggerFactory.getLogger(LeadHubspotSyncListener.class);
    private final HubspotService hubspotService;

    public LeadHubspotSyncListener(HubspotService hubspotService){
        this.hubspotService = hubspotService;
    }

    @TransactionalEventListener(phase = org.springframework.transaction.event.TransactionPhase.AFTER_COMMIT)
    @Async // This method will run asynchronously after the transaction is committed. It calls the
    public void onLeadCreated(LeadCreatedEvent event){
        Lead lead = event.getLead();
        log.info("LeadHubspotSyncListener triggered for lead id={}", lead.getId());
        try {
            hubspotService.syncLead(lead); //stub call
        } catch (Exception e) {
            log.error("Error syncing lead {} to HubSpot", lead.getId(), e.getMessage(), e);
        }
    }
}