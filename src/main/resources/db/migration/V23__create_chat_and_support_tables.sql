CREATE TABLE IF NOT EXISTS beworking.chat_messages (
    id              BIGSERIAL PRIMARY KEY,
    tenant_id       BIGINT NOT NULL,
    user_email      VARCHAR(255) NOT NULL,
    role            VARCHAR(20) NOT NULL,  -- 'user' or 'assistant'
    content         TEXT NOT NULL,
    created_at      TIMESTAMP DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_chat_messages_tenant
    ON beworking.chat_messages (tenant_id, created_at);

CREATE TABLE IF NOT EXISTS beworking.support_tickets (
    id              BIGSERIAL PRIMARY KEY,
    tenant_id       BIGINT NOT NULL,
    user_email      VARCHAR(255) NOT NULL,
    subject         VARCHAR(500) NOT NULL,
    message         TEXT NOT NULL,
    status          VARCHAR(50) NOT NULL DEFAULT 'open',
    priority        VARCHAR(20) NOT NULL DEFAULT 'normal',
    admin_notes     TEXT,
    created_at      TIMESTAMP DEFAULT NOW(),
    updated_at      TIMESTAMP DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_support_tickets_tenant
    ON beworking.support_tickets (tenant_id, status);
