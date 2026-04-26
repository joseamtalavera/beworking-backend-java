package com.beworking.bekey;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

@Entity
@Table(name = "bekey_member_identity", schema = "beworking")
public class BeKeyMemberIdentity {

    @Id
    @Column(name = "contact_id")
    private Long contactId;

    @Column(name = "akiles_member_id", nullable = false, unique = true, length = 64)
    private String akilesMemberId;

    @Column(name = "akiles_pin_id", length = 64)
    private String akilesPinId;

    @Column(name = "created_at", insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    public BeKeyMemberIdentity() {}

    public Long getContactId() { return contactId; }
    public void setContactId(Long contactId) { this.contactId = contactId; }

    public String getAkilesMemberId() { return akilesMemberId; }
    public void setAkilesMemberId(String akilesMemberId) { this.akilesMemberId = akilesMemberId; }

    public String getAkilesPinId() { return akilesPinId; }
    public void setAkilesPinId(String akilesPinId) { this.akilesPinId = akilesPinId; }

    public OffsetDateTime getCreatedAt() { return createdAt; }

    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
