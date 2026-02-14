-- Add package support columns to mailroom_documents
ALTER TABLE beworking.mailroom_documents
    ADD COLUMN document_type VARCHAR(16) NOT NULL DEFAULT 'mail',
    ADD COLUMN pickup_code   VARCHAR(16),
    ADD COLUMN picked_up_at  TIMESTAMPTZ;

-- Unique partial index: pickup_code must be unique when not null
CREATE UNIQUE INDEX IF NOT EXISTS idx_mailroom_documents_pickup_code
    ON beworking.mailroom_documents (pickup_code)
    WHERE pickup_code IS NOT NULL;
