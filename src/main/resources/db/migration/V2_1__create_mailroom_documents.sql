CREATE TABLE IF NOT EXISTS beworking.mailroom_documents (
    id UUID PRIMARY KEY,
    tenant_id UUID,
    title VARCHAR(255) NOT NULL,
    sender VARCHAR(255),
    received_at TIMESTAMPTZ,
    status VARCHAR(32) NOT NULL,
    last_notified_at TIMESTAMPTZ,
    pages INTEGER,
    avatar_color VARCHAR(32),
    stored_file_name VARCHAR(255) NOT NULL,
    original_file_name VARCHAR(255),
    content_type VARCHAR(128),
    file_size_bytes BIGINT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_mailroom_documents_tenant ON beworking.mailroom_documents(tenant_id);
CREATE INDEX IF NOT EXISTS idx_mailroom_documents_received_at ON beworking.mailroom_documents(received_at DESC);
