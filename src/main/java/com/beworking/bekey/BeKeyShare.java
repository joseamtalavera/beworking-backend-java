package com.beworking.bekey;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

/**
 * A member sharing their BeKey access with a guest for a bounded window (#243).
 * The actual key is the bekey_access grant (source='shared') referenced by
 * {@link #accessId}; this row is the human-facing record ("shared by me").
 */
@Entity
@Table(name = "bekey_shares", schema = "beworking")
public class BeKeyShare {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sharer_contact_id", nullable = false)
    private Long sharerContactId;

    @Column(name = "guest_contact_id", nullable = false)
    private Long guestContactId;

    @Column(name = "guest_email", nullable = false, length = 255)
    private String guestEmail;

    @Column(name = "guest_name", length = 255)
    private String guestName;

    @Column(name = "member_group_id", nullable = false)
    private Long memberGroupId;

    @Column(name = "access_id")
    private Long accessId;

    @Column(name = "starts_at", nullable = false)
    private OffsetDateTime startsAt;

    @Column(name = "ends_at", nullable = false)
    private OffsetDateTime endsAt;

    @Column(name = "revoked_at")
    private OffsetDateTime revokedAt;

    @Column(name = "created_at", insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    public BeKeyShare() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getSharerContactId() { return sharerContactId; }
    public void setSharerContactId(Long v) { this.sharerContactId = v; }

    public Long getGuestContactId() { return guestContactId; }
    public void setGuestContactId(Long v) { this.guestContactId = v; }

    public String getGuestEmail() { return guestEmail; }
    public void setGuestEmail(String v) { this.guestEmail = v; }

    public String getGuestName() { return guestName; }
    public void setGuestName(String v) { this.guestName = v; }

    public Long getMemberGroupId() { return memberGroupId; }
    public void setMemberGroupId(Long v) { this.memberGroupId = v; }

    public Long getAccessId() { return accessId; }
    public void setAccessId(Long v) { this.accessId = v; }

    public OffsetDateTime getStartsAt() { return startsAt; }
    public void setStartsAt(OffsetDateTime v) { this.startsAt = v; }

    public OffsetDateTime getEndsAt() { return endsAt; }
    public void setEndsAt(OffsetDateTime v) { this.endsAt = v; }

    public OffsetDateTime getRevokedAt() { return revokedAt; }
    public void setRevokedAt(OffsetDateTime v) { this.revokedAt = v; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
}
