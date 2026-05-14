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

    // Phone is optional now (OV-interest forms still send it; the generic
    // contact form does not). Validation for format is enforced on the
    // request DTO when present.
    private String phone;

    @Column(length = 120)
    private String subject;

    @Column(columnDefinition = "TEXT")
    private String message;

    @Column(length = 40)
    private String source;

    private Instant createdAt = Instant.now();

    // Pipeline state — see V56. Sales team uses these to track lead progress.
    @Column(length = 40, nullable = false)
    private String status = "Nuevo";

    @Column(columnDefinition = "TEXT")
    private String notes;

    @Column(name = "status_changed_at")
    private Instant statusChangedAt;

    // Lead nurture cron: tracks the 4-touch email sequence sent to leads
    // in 'Contactado'. Mirrors the abandonment_email_count pattern on
    // contact_profiles. See LeadNurtureScheduler.
    @Column(name = "nurture_email_count", nullable = false)
    private int nurtureEmailCount = 0;

    @Column(name = "last_nurture_email_at")
    private Instant lastNurtureEmailAt;

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

    public String getSubject() { return subject; }
    public void setSubject(String subject) { this.subject = subject; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public String getStatus() { return status; }
    public void setStatus(String status) {
        this.status = (status == null || status.isBlank()) ? "Nuevo" : status;
    }

    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }

    public Instant getStatusChangedAt() { return statusChangedAt; }
    public void setStatusChangedAt(Instant statusChangedAt) {
        this.statusChangedAt = statusChangedAt;
    }

    public int getNurtureEmailCount() { return nurtureEmailCount; }
    public void setNurtureEmailCount(int nurtureEmailCount) { this.nurtureEmailCount = nurtureEmailCount; }

    public Instant getLastNurtureEmailAt() { return lastNurtureEmailAt; }
    public void setLastNurtureEmailAt(Instant lastNurtureEmailAt) { this.lastNurtureEmailAt = lastNurtureEmailAt; }

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
