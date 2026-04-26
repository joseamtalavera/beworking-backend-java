package com.beworking.bekey;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.OffsetDateTime;

@Entity
@Table(name = "bekey_events", schema = "beworking")
public class BeKeyEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "akiles_event_id", nullable = false, unique = true, length = 64)
    private String akilesEventId;

    @Column(name = "contact_id")
    private Long contactId;

    @Column(name = "device_id")
    private Long deviceId;

    @Column(name = "event_type", nullable = false, length = 64)
    private String eventType;

    @Column(name = "occurred_at", nullable = false)
    private OffsetDateTime occurredAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "raw", nullable = false, columnDefinition = "jsonb")
    private String raw;

    @Column(name = "created_at", insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    public BeKeyEvent() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getAkilesEventId() { return akilesEventId; }
    public void setAkilesEventId(String akilesEventId) { this.akilesEventId = akilesEventId; }

    public Long getContactId() { return contactId; }
    public void setContactId(Long contactId) { this.contactId = contactId; }

    public Long getDeviceId() { return deviceId; }
    public void setDeviceId(Long deviceId) { this.deviceId = deviceId; }

    public String getEventType() { return eventType; }
    public void setEventType(String eventType) { this.eventType = eventType; }

    public OffsetDateTime getOccurredAt() { return occurredAt; }
    public void setOccurredAt(OffsetDateTime occurredAt) { this.occurredAt = occurredAt; }

    public String getRaw() { return raw; }
    public void setRaw(String raw) { this.raw = raw; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
}
