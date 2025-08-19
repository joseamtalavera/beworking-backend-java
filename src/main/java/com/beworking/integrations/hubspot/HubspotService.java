package com.beworking.integrations.hubspot; // Import for Hubspot integration service

import com.beworking.leads.Lead; 
import com.beworking.leads.LeadRepository;
import org.slf4j.Logger; 
import org.slf4j.LoggerFactory; 
import org.springframework.beans.factory.annotation.Value; // Import for Value annotation to inject properties from application.properties
import org.springframework.stereotype.Service;

@Service
public class HubspotService {
    private static final Logger log = LoggerFactory.getLogger(HubspotService.class);

    private final LeadRepository leadRepository;

    @Value("${beworking.api.baseUrl: https://api.hubapi.com}")
    private String hubspotBaseUrl;
    @Value ("${beworking.api.token:}")
    private String hubspotToken;

    public HubspotService(LeadRepository leadRepository) {
        this.leadRepository = leadRepository;
    }

    /**
     * Stub sync. For Step 1 this only logs and returns a stub result.
     * Later we'll implement the real HTTP call and update lead fields.
     */

     public HubspotSyncResult syncLead(Lead lead) {
        log.info("HubspotService.synclead stub called for lead id={}", lead.getId());
        // Here we would normally make an HTTP call to Hubspot API to sync the lead
        return new HubspotSyncResult(false, null, "stub-not-called");
     }
}