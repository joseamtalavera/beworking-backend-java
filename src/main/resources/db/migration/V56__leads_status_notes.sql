-- V56: Lead pipeline status + notes (2026-05-06)
--
-- Turns Leads from a notification feed into a working CRM list:
--   status: where the lead is in the sales funnel
--     Nuevo       — just landed, nobody has touched it
--     Contactado  — sales has reached out, waiting on response
--     Calificado  — qualified, ready to convert / negotiating
--     Convertido  — became a contact_profile (also stamped on convert)
--     No-go       — rejected / not a fit / unresponsive
--   notes: free text the sales team can write while working the lead.
--
-- Default 'Nuevo' so existing rows aren't blank.

ALTER TABLE beworking.leads
  ADD COLUMN IF NOT EXISTS status VARCHAR(40) NOT NULL DEFAULT 'Nuevo';

ALTER TABLE beworking.leads
  ADD COLUMN IF NOT EXISTS notes TEXT NULL;

-- Track when status last changed for audit + future stale-lead crons.
ALTER TABLE beworking.leads
  ADD COLUMN IF NOT EXISTS status_changed_at TIMESTAMP NULL;
