CREATE TABLE IF NOT EXISTS beworking.ai_usage (
    id              BIGSERIAL PRIMARY KEY,
    tenant_id       BIGINT NOT NULL,
    queries_used    INT NOT NULL DEFAULT 0,
    period_start    DATE NOT NULL,
    period_end      DATE NOT NULL,
    created_at      TIMESTAMP DEFAULT NOW(),
    updated_at      TIMESTAMP DEFAULT NOW()
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_ai_usage_tenant_period
    ON beworking.ai_usage (tenant_id, period_start);
