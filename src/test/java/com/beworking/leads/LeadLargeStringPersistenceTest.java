package com.beworking.leads;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
public class LeadLargeStringPersistenceTest {

    @Autowired
    private LeadRepository leadRepository;

    @Test
    public void largeHubspotError_isPersistedAndRetrieved() {
        // Build a large string (~200 KB)
        StringBuilder sb = new StringBuilder();
        IntStream.range(0, 200_000 / 10).forEach(i -> sb.append("0123456789"));
        String large = sb.toString();

        Lead lead = new Lead();
        lead.setName("Large Test");
        lead.setEmail("large@example.com");
        lead.setPhone("+10000000000");
        lead.setHubspotError(large);

        Lead saved = leadRepository.save(lead);

        Lead found = leadRepository.findById(saved.getId()).orElseThrow();

        assertThat(found.getHubspotError()).isNotNull();
        assertThat(found.getHubspotError().length()).isEqualTo(large.length());
        assertThat(found.getHubspotError()).startsWith("0123456789");
    }
}
