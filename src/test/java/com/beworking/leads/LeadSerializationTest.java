package com.beworking.leads;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that a Lead can be serialized to JSON and deserialized back without losing data.
 */
public class LeadSerializationTest {

    @Test
    public void serializationRoundTrip_preservesFields() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        // Ensure proper Instant handling
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        Lead lead = new Lead();
        lead.setName("Alice Example");
        lead.setEmail("alice@example.com");
        lead.setPhone("+1234567890");
        lead.setCreatedAt(Instant.parse("2020-01-01T12:00:00Z"));
        lead.setHubspotSyncStatus(SyncStatus.PENDING);
        lead.setHubspotId("hs_123");
        lead.setHubspotError("no error");
        lead.setHubspotSyncAttempts(1);
        lead.setLastHubspotAttemptAt(Instant.parse("2020-01-02T12:00:00Z"));

        String json = mapper.writeValueAsString(lead);
        Lead round = mapper.readValue(json, Lead.class);

        // Basic field checks
        assertThat(round.getName()).isEqualTo(lead.getName());
        assertThat(round.getEmail()).isEqualTo(lead.getEmail());
        assertThat(round.getPhone()).isEqualTo(lead.getPhone());
        assertThat(round.getCreatedAt()).isEqualTo(lead.getCreatedAt());
        assertThat(round.getHubspotSyncStatus()).isEqualTo(lead.getHubspotSyncStatus());
        assertThat(round.getHubspotId()).isEqualTo(lead.getHubspotId());
        assertThat(round.getHubspotError()).isEqualTo(lead.getHubspotError());
        assertThat(round.getHubspotSyncAttempts()).isEqualTo(lead.getHubspotSyncAttempts());
        assertThat(round.getLastHubspotAttemptAt()).isEqualTo(lead.getLastHubspotAttemptAt());
    }
}
