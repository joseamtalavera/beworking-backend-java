-- #243 Share my access: a member lends their BeKey access to a guest for a
-- bounded window. A share spawns a normal bekey_access grant (source='shared')
-- for the guest's contact, so the existing door-open authz enforces it.

-- 1. Allow the new 'shared' source on grants.
ALTER TABLE beworking.bekey_access DROP CONSTRAINT IF EXISTS bekey_access_source_chk;
ALTER TABLE beworking.bekey_access ADD CONSTRAINT bekey_access_source_chk
    CHECK (source IN ('subscription','booking','manual','shared'));

-- 2. The share record: who shared, with whom, which group, the window, and the
--    spawned grant. access_id is nulled if the grant row is later removed.
CREATE TABLE beworking.bekey_shares (
    id                  BIGSERIAL    PRIMARY KEY,
    sharer_contact_id   BIGINT       NOT NULL REFERENCES beworking.contact_profiles(id) ON DELETE CASCADE,
    guest_contact_id    BIGINT       NOT NULL REFERENCES beworking.contact_profiles(id) ON DELETE CASCADE,
    guest_email         VARCHAR(255) NOT NULL,
    guest_name          VARCHAR(255) NULL,
    member_group_id     BIGINT       NOT NULL REFERENCES beworking.bekey_member_groups(id),
    access_id           BIGINT       NULL REFERENCES beworking.bekey_access(id) ON DELETE SET NULL,
    starts_at           TIMESTAMPTZ  NOT NULL,
    ends_at             TIMESTAMPTZ  NOT NULL,
    revoked_at          TIMESTAMPTZ  NULL,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_bekey_shares_sharer_active
    ON beworking.bekey_shares (sharer_contact_id) WHERE revoked_at IS NULL;
