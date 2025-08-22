package com.beworking.leads;

import jakarta.persistence.*;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import java.util.UUID;
import com.beworking.leads.SyncStatus;

@Entity
@Table(name = "leads", schema = "beworking")
public class Lead {

    @Id
    @GeneratedValue
    private UUID id;

    @NotBlank
    private String name;

    @NotBlank
    @Email
    private String email;

    @NotBlank
    private String phone;

    private Instant createdAt = Instant.now();

    // HubSpot specific fields

   @Enumerated(EnumType.STRING)
    @Column(name = "hubspot_sync_status")
    private SyncStatus hubspotSyncStatus;

    @Column(name = "hubspot_id")
    private String hubspotId;

    @Column(name = "hubspot_synced_at")
    private Instant hubspotSyncedAt;

    @Column(name = "hubspot_error", columnDefinition = "TEXT")
    private String hubspotError;

    @Column(name = "hubspot_sync_attempts")
    private Integer hubspotSyncAttempts;

    @Column(name = "last_hubspot_attempt_at")
    private Instant lastHubspotAttemptAt;


    // Getters and Setters

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    // HubSpot specific getters and setters

    public SyncStatus getHubspotSyncStatus() {
        return hubspotSyncStatus;
    }
    public void setHubspotSyncStatus(SyncStatus hubspotSyncStatus) {
        this.hubspotSyncStatus = hubspotSyncStatus;
    }

    public String getHubspotId() {
        return hubspotId;
    }
    public void setHubspotId(String hubspotId) {
        this.hubspotId = hubspotId;
    }

    public Instant getHubspotSyncedAt() {
        return hubspotSyncedAt;
    }
    public void setHubspotSyncedAt(Instant hubspotSyncedAt) {
        this.hubspotSyncedAt = hubspotSyncedAt;
    }

    public String getHubspotError() {
        return hubspotError;
    }
    public void setHubspotError(String hubspotError) {
        this.hubspotError = hubspotError;
    }

    public Integer getHubspotSyncAttempts() {
        return hubspotSyncAttempts;
    }
    public void setHubspotSyncAttempts(Integer hubspotSyncAttempts) {
        this.hubspotSyncAttempts = hubspotSyncAttempts;
    }

    public Instant getLastHubspotAttemptAt() {
        return lastHubspotAttemptAt;
    }
    public void setLastHubspotAttemptAt(Instant lastHubspotAttemptAt) {
        this.lastHubspotAttemptAt = lastHubspotAttemptAt;
    }

    // Patch: add setHubspotAttemptAt for compatibility
    public void setHubspotAttemptAt(Instant instant) {
        this.lastHubspotAttemptAt = instant;
    }

}
