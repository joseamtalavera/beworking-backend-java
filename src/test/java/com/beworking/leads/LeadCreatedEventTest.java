package com.beworking.leads;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link LeadCreatedEvent}.
 *
 * <p>These tests verify:
 * <ul>
 *   <li>The constructor sets the {@code lead} field correctly.</li>
 *   <li>The {@code getLead()} method returns the same instance passed to the constructor.</li>
 *   <li>Null handling: constructing with {@code null} lead is allowed and {@code getLead()} returns null.</li>
 * </ul>
 *
 * <p>These tests ensure the event class is a reliable data carrier for downstream listeners.
 */
class LeadCreatedEventTest {
    @Test
    void constructor_setsLead() {
        Lead lead = new Lead();
        LeadCreatedEvent event = new LeadCreatedEvent(lead);
        assertThat(event.getLead()).isSameAs(lead);
    }

    @Test
    void getLead_returnsNullIfConstructedWithNull() {
        LeadCreatedEvent event = new LeadCreatedEvent(null);
        assertThat(event.getLead()).isNull();
    }
}
