-- V53: Re-engagement campaign for Inactivo contacts (2026-05-06)
--
-- Counterpart to the Potencial recovery cron — once a contact is Inactivo,
-- send a soft "long time no see" email every 6 months, max 3 times. After
-- the third send (~18 months total) they're really gone and we stop. Admin
-- can clear the timestamp manually to restart the sequence if needed.

ALTER TABLE beworking.contact_profiles
  ADD COLUMN IF NOT EXISTS reengagement_email_count INTEGER NOT NULL DEFAULT 0;

ALTER TABLE beworking.contact_profiles
  ADD COLUMN IF NOT EXISTS last_reengagement_email_at TIMESTAMP NULL;
