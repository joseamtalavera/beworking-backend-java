ALTER TABLE beworking.leads
    ADD COLUMN hubspot_id VARCHAR(255),
    ADD COLUMN hubspot_sync_status VARCHAR(32) DEFAULT 'PENDING',
    ADD COLUMN hubspot_synced_at TIMESTAMP, 
    ADD COLUMN hubspot_error TEXT,
    ADD COLUMN hubspot_sync_attempts INTEGER DEFAULT 0,
    ADD COLUMN last_hubspot_attempt_at TIMESTAMP;