package com.beworking.leads;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import static org.junit.jupiter.api.Assertions.*;


/**
 * Unit tests for {@link LeadRepository}.
 * <p>
 * Verifies that Lead entities can be persisted and retrieved correctly using JPA.
 * <p>
 * Additional tests may be needed if custom queries are added to the repository.
 */
@DataJpaTest
class LeadRepositoryTest {

    @Autowired
    LeadRepository repo;

    /**
     * Verifies that a Lead can be saved and then retrieved with all important fields correctly persisted.
     */
    @Test
    void saveAndRetrieveLead() {
        Lead l = new Lead();
        l.setName("Persisted");
        l.setEmail("p@example.com");
        l.setPhone("999999");
        l.setHubspotSyncStatus(SyncStatus.SYNCED);

        Lead saved = repo.save(l);
        assertNotNull(saved.getId());

        Lead found = repo.findById(saved.getId()).orElseThrow();
        assertEquals("Persisted", found.getName());
        assertEquals(SyncStatus.SYNCED, found.getHubspotSyncStatus());
        assertNotNull(found.getCreatedAt());
    }
}
