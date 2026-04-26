-- BeKey: white-labeled access control built on top of Akiles.
-- "Akiles" is intentionally kept out of any user-visible string.
-- References to the upstream provider are confined to akiles_* columns.

CREATE TABLE beworking.bekey_devices (
    id                BIGSERIAL    PRIMARY KEY,
    akiles_gadget_id  VARCHAR(64)  NOT NULL UNIQUE,
    akiles_site_id    VARCHAR(64)  NOT NULL,
    name              VARCHAR(255) NOT NULL,
    action_id         VARCHAR(64)  NOT NULL DEFAULT 'open',
    room_id           BIGINT       NULL REFERENCES beworking.rooms(id) ON DELETE SET NULL,
    online            BOOLEAN      NOT NULL DEFAULT FALSE,
    last_seen_at      TIMESTAMPTZ  NULL,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_bekey_devices_room ON beworking.bekey_devices(room_id);

-- An Akiles member group used as a permission bundle.
-- Pre-created in the Akiles dashboard; we just reference its id here.
CREATE TABLE beworking.bekey_member_groups (
    id                BIGSERIAL    PRIMARY KEY,
    akiles_group_id   VARCHAR(64)  NOT NULL UNIQUE,
    label             VARCHAR(255) NOT NULL,
    scope             VARCHAR(64)  NOT NULL,
    notes             TEXT         NULL,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- A BeWorking contact's current or historical access grant.
-- One row per grant; revocation marks revoked_at instead of deleting.
CREATE TABLE beworking.bekey_access (
    id                     BIGSERIAL    PRIMARY KEY,
    contact_id             BIGINT       NOT NULL REFERENCES beworking.contact_profiles(id) ON DELETE CASCADE,
    akiles_member_id       VARCHAR(64)  NOT NULL,
    akiles_association_id  VARCHAR(64)  NOT NULL,
    member_group_id        BIGINT       NOT NULL REFERENCES beworking.bekey_member_groups(id),
    source                 VARCHAR(32)  NOT NULL,
    source_ref             BIGINT       NULL,
    starts_at              TIMESTAMPTZ  NOT NULL,
    ends_at                TIMESTAMPTZ  NULL,
    revoked_at             TIMESTAMPTZ  NULL,
    created_at             TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at             TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT bekey_access_source_chk CHECK (source IN ('subscription','booking','manual'))
);
CREATE INDEX idx_bekey_access_contact ON beworking.bekey_access(contact_id);
CREATE INDEX idx_bekey_access_active  ON beworking.bekey_access(contact_id) WHERE revoked_at IS NULL;
CREATE INDEX idx_bekey_access_source  ON beworking.bekey_access(source, source_ref);

-- Stable BeWorking-contact-to-Akiles-member identity.
-- Lives separately from bekey_access because PINs and member identity
-- outlive any single grant cycle.
CREATE TABLE beworking.bekey_member_identity (
    contact_id        BIGINT       PRIMARY KEY REFERENCES beworking.contact_profiles(id) ON DELETE CASCADE,
    akiles_member_id  VARCHAR(64)  NOT NULL UNIQUE,
    akiles_pin_id     VARCHAR(64)  NULL,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- Mirror of Akiles webhook events for admin activity log + idempotency.
CREATE TABLE beworking.bekey_events (
    id               BIGSERIAL    PRIMARY KEY,
    akiles_event_id  VARCHAR(64)  NOT NULL UNIQUE,
    contact_id       BIGINT       NULL REFERENCES beworking.contact_profiles(id) ON DELETE SET NULL,
    device_id        BIGINT       NULL REFERENCES beworking.bekey_devices(id) ON DELETE SET NULL,
    event_type       VARCHAR(64)  NOT NULL,
    occurred_at      TIMESTAMPTZ  NOT NULL,
    raw              JSONB        NOT NULL,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_bekey_events_contact_time ON beworking.bekey_events(contact_id, occurred_at DESC);
CREATE INDEX idx_bekey_events_device_time  ON beworking.bekey_events(device_id, occurred_at DESC);
