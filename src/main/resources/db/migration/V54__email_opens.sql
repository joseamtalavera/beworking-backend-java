-- V54: Email open tracking via 1x1 pixel (2026-05-06)
--
-- One row per pixel hit. Multiple opens by the same recipient produce multiple
-- rows; the analytics view aggregates by contact_id + template_type +
-- template_number and treats DISTINCT contact_id as the open rate.

CREATE TABLE IF NOT EXISTS beworking.email_opens (
    id              BIGSERIAL PRIMARY KEY,
    contact_id      BIGINT       NOT NULL,
    template_type   VARCHAR(32)  NOT NULL,    -- 'recovery' or 'reengagement'
    template_number INTEGER      NOT NULL,    -- 1..4 for recovery, 1..3 for reengagement
    opened_at       TIMESTAMP    NOT NULL DEFAULT NOW(),
    user_agent      TEXT         NULL,
    ip              VARCHAR(64)  NULL
);

CREATE INDEX IF NOT EXISTS idx_email_opens_contact
    ON beworking.email_opens (contact_id);

CREATE INDEX IF NOT EXISTS idx_email_opens_opened_at
    ON beworking.email_opens (opened_at);

CREATE INDEX IF NOT EXISTS idx_email_opens_type_n
    ON beworking.email_opens (template_type, template_number);
