-- V50: extend leads table to support both OV-interest captures and the
-- generic contact form. Contact form does not collect phone, but does
-- collect subject + message + a source tag for behavioral branching
-- (per-subject email templates, future funnel reporting).
--
-- Idempotent: ALTER ... DROP NOT NULL is a no-op if already nullable;
-- ADD COLUMN IF NOT EXISTS skips on re-runs. Safe across envs.

ALTER TABLE beworking.leads
    ALTER COLUMN phone DROP NOT NULL;

ALTER TABLE beworking.leads
    ADD COLUMN IF NOT EXISTS subject VARCHAR(120);

ALTER TABLE beworking.leads
    ADD COLUMN IF NOT EXISTS message TEXT;

ALTER TABLE beworking.leads
    ADD COLUMN IF NOT EXISTS source VARCHAR(40);

-- Index on source so admin "filter by lead origin" queries stay fast as
-- the table grows. Lead volume is low today but planned to scale with
-- the new lead-CRM tab.
CREATE INDEX IF NOT EXISTS idx_leads_source ON beworking.leads (source);
