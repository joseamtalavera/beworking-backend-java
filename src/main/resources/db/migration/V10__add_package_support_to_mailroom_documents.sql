-- Add package support columns to mailroom_documents
ALTER TABLE beworking.mailroom_documents
    ADD COLUMN IF NOT EXISTS document_type VARCHAR(16) NOT NULL DEFAULT 'MAIL',
    ADD COLUMN IF NOT EXISTS pickup_code   VARCHAR(16),
    ADD COLUMN IF NOT EXISTS picked_up_at  TIMESTAMPTZ;

-- Update status check constraint to include PICKED_UP
ALTER TABLE beworking.mailroom_documents
    DROP CONSTRAINT IF EXISTS mailroom_documents_status_check;
ALTER TABLE beworking.mailroom_documents
    ADD CONSTRAINT mailroom_documents_status_check
    CHECK (status::text = ANY (ARRAY['SCANNED','NOTIFIED','VIEWED','PICKED_UP']::text[]));

-- Unique partial index: pickup_code must be unique when not null
CREATE UNIQUE INDEX IF NOT EXISTS idx_mailroom_documents_pickup_code
    ON beworking.mailroom_documents (pickup_code)
    WHERE pickup_code IS NOT NULL;
