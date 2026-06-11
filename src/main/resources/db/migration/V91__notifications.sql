-- Internal formal communications ("Notificaciones") from BeWorking to a client.
-- Mirrors mailroom_documents: recipient linked by contact_email, tenant_id optional.
-- Lifecycle: CREATED -> SENT -> READ -> ACKNOWLEDGED, with timestamps for proof of receipt.
CREATE TABLE IF NOT EXISTS beworking.notifications (
    id              UUID PRIMARY KEY,
    contact_email   VARCHAR(255) NOT NULL,
    tenant_id       UUID,
    subject         VARCHAR(255) NOT NULL,
    body            TEXT NOT NULL,
    status          VARCHAR(32) NOT NULL DEFAULT 'CREATED'
                    CHECK (status IN ('CREATED','SENT','READ','ACKNOWLEDGED')),
    created_by      VARCHAR(255),
    sent_at         TIMESTAMPTZ,
    read_at         TIMESTAMPTZ,
    acknowledged_at TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_notifications_contact_email
    ON beworking.notifications (LOWER(contact_email));
CREATE INDEX IF NOT EXISTS idx_notifications_tenant
    ON beworking.notifications (tenant_id);
