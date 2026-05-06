-- V52: Collapse contact status to a 3-state funnel (2026-05-06)
--
-- Funnel:
--   Potencial — started a paid flow (OV signup / room booking) and dropped.
--               Recovery cron target. Has demonstrated payment intent.
--   Activo    — has at least one invoice. Set when payment lands.
--   Inactivo  — free register that never started a paid flow, OR was Potencial
--               for 7+ days without paying, OR was Activo and the subscription
--               was cancelled.
--
-- Retired statuses:
--   "Pendiente Pago"   → renamed to Potencial (same semantics)
--   "Abandono"         → Inactivo (no backend ever wrote it; phantom UI label)
--   "Trial"            → Inactivo (deprecated)
--   "Lista de Espera"  → Inactivo (deprecated)
--   NULL / ""          → Inactivo (free registers default here)
--
-- Existing 'Potencial' rows (cold leads) are left untouched; under the new
-- model they sit alongside drop-outs under the same label, which is correct:
-- both are "people we'd like to convert".

UPDATE beworking.contact_profiles
   SET status = 'Potencial'
 WHERE status = 'Pendiente Pago';

UPDATE beworking.contact_profiles
   SET status = 'Inactivo'
 WHERE status IS NULL
    OR status = ''
    OR status IN ('Abandono', 'Trial', 'Lista de Espera');

-- Multi-touch recovery schedule needs a counter and a last-sent timestamp.
-- The original abandonment_email_sent_at column (V51) becomes redundant once
-- last_recovery_email_at is in place, but we leave it for backward compat —
-- the scheduler ignores it from now on.
ALTER TABLE beworking.contact_profiles
  ADD COLUMN IF NOT EXISTS abandonment_email_count INTEGER NOT NULL DEFAULT 0;

ALTER TABLE beworking.contact_profiles
  ADD COLUMN IF NOT EXISTS last_recovery_email_at TIMESTAMP NULL;

-- Backfill: rows that already received the V51 one-shot email should start at
-- count=1 so they don't receive email #1 again.
UPDATE beworking.contact_profiles
   SET abandonment_email_count = 1,
       last_recovery_email_at  = abandonment_email_sent_at
 WHERE abandonment_email_sent_at IS NOT NULL
   AND abandonment_email_count = 0;
