package com.beworking.bekey;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

@Entity
@Table(name = "bekey_devices", schema = "beworking")
public class BeKeyDevice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "akiles_gadget_id", nullable = false, unique = true, length = 64)
    private String akilesGadgetId;

    @Column(name = "akiles_site_id", nullable = false, length = 64)
    private String akilesSiteId;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "action_id", nullable = false, length = 64)
    private String actionId = "open";

    @Column(name = "room_id")
    private Long roomId;

    @Column(name = "online", nullable = false)
    private Boolean online = false;

    @Column(name = "last_seen_at")
    private OffsetDateTime lastSeenAt;

    @Column(name = "created_at", insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private OffsetDateTime updatedAt;

    public BeKeyDevice() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getAkilesGadgetId() { return akilesGadgetId; }
    public void setAkilesGadgetId(String akilesGadgetId) { this.akilesGadgetId = akilesGadgetId; }

    public String getAkilesSiteId() { return akilesSiteId; }
    public void setAkilesSiteId(String akilesSiteId) { this.akilesSiteId = akilesSiteId; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getActionId() { return actionId; }
    public void setActionId(String actionId) { this.actionId = actionId; }

    public Long getRoomId() { return roomId; }
    public void setRoomId(Long roomId) { this.roomId = roomId; }

    public Boolean getOnline() { return online; }
    public void setOnline(Boolean online) { this.online = online; }

    public OffsetDateTime getLastSeenAt() { return lastSeenAt; }
    public void setLastSeenAt(OffsetDateTime lastSeenAt) { this.lastSeenAt = lastSeenAt; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
