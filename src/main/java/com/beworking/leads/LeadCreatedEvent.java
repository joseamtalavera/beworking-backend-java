package com.beworking.leads;

public class LeadCreatedEvent {
    private final Lead lead;

    public LeadCreatedEvent(Lead lead) {
        this.lead = lead;
    }

    public Lead getLead() {
        return lead;
    }
}