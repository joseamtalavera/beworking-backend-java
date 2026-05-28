-- Persist Pendiente counts and the underlying invoice rows on each reconciliation
-- run so the dashboard ReconciliationCard reads the same values the daily email
-- shows. Pre-V71 the card filtered all invoices client-side, which drifted from
-- the server SQL filter (subscription categories only) and caused the card to
-- show meeting-room one-offs alongside actual sub Pendientes.
ALTER TABLE beworking.reconciliation_results
    ADD COLUMN IF NOT EXISTS pendiente_count   INTEGER         NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS pendiente_amount  NUMERIC(12, 2)  NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS pending_invoices  JSONB           NOT NULL DEFAULT '[]'::jsonb;
