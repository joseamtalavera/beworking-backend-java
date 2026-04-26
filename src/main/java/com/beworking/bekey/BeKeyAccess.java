package com.beworking.bekey;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

@Entity
@Table(name = "bekey_access", schema = "beworking")
public class BeKeyAccess {

    public enum Source { subscription, booking, manual }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "contact_id", nullable = false)
    private Long contactId;

    @Column(name = "akiles_member_id", nullable = false, length = 64)
    private String akilesMemberId;

    @Column(name = "akiles_association_id", nullable = false, length = 64)
    private String akilesAssociationId;

    @Column(name = "member_group_id", nullable = false)
    private Long memberGroupId;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 32)
    private Source source;

    @Column(name = "source_ref")
    private Long sourceRef;

    @Column(name = "starts_at", nullable = false)
    private OffsetDateTime startsAt;

    @Column(name = "ends_at")
    private OffsetDateTime endsAt;

    @Column(name = "revoked_at")
    private OffsetDateTime revokedAt;

    @Column(name = "created_at", insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    public BeKeyAccess() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getContactId() { return contactId; }
    public void setContactId(Long contactId) { this.contactId = contactId; }

    public String getAkilesMemberId() { return akilesMemberId; }
    public void setAkilesMemberId(String akilesMemberId) { this.akilesMemberId = akilesMemberId; }

    public String getAkilesAssociationId() { return akilesAssociationId; }
    public void setAkilesAssociationId(String akilesAssociationId) { this.akilesAssociationId = akilesAssociationId; }

    public Long getMemberGroupId() { return memberGroupId; }
    public void setMemberGroupId(Long memberGroupId) { this.memberGroupId = memberGroupId; }

    public Source getSource() { return source; }
    public void setSource(Source source) { this.source = source; }

    public Long getSourceRef() { return sourceRef; }
    public void setSourceRef(Long sourceRef) { this.sourceRef = sourceRef; }

    public OffsetDateTime getStartsAt() { return startsAt; }
    public void setStartsAt(OffsetDateTime startsAt) { this.startsAt = startsAt; }

    public OffsetDateTime getEndsAt() { return endsAt; }
    public void setEndsAt(OffsetDateTime endsAt) { this.endsAt = endsAt; }

    public OffsetDateTime getRevokedAt() { return revokedAt; }
    public void setRevokedAt(OffsetDateTime revokedAt) { this.revokedAt = revokedAt; }

    public OffsetDateTime getCreatedAt() { return createdAt; }

    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
