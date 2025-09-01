package com.beworking.hubspot;

import com.beworking.leads.Lead;
import com.beworking.leads.SyncStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

public class LeadHubspotFieldsTest {

    @Test
    void hubspotFieldsGettersAndSettersWork() {
        Lead lead = new Lead();

        // Set HubSpot related fields
        lead.setHubspotId("hs_12345");
        Instant now = Instant.now();
        lead.setHubspotSyncedAt(now);
        lead.setHubspotError("error message");
        lead.setHubspotSyncAttempts(2);
        lead.setHubspotSyncStatus(SyncStatus.SYNCED);

        // Verify getters return the values set
        assertEquals("hs_12345", lead.getHubspotId());
        assertEquals(now, lead.getHubspotSyncedAt());
        assertEquals("error message", lead.getHubspotError());
        assertEquals(Integer.valueOf(2), lead.getHubspotSyncAttempts());
        assertEquals(SyncStatus.SYNCED, lead.getHubspotSyncStatus());
    }

    @Test
    void compatibilitySetterSetsLastAttemptAt() {
        Lead lead = new Lead();
        Instant attempt = Instant.now();

        // Use compatibility method
        lead.setHubspotAttemptAt(attempt);

        // Verify lastHubspotAttemptAt was set
        assertEquals(attempt, lead.getLastHubspotAttemptAt());
    }
}
