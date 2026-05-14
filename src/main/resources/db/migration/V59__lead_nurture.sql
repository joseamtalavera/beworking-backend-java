-- Lead nurture cron: 4 emails over 7 days to leads in 'Contactado',
-- then 23 days of silence, then LeadAgingScheduler flips to 'No-go' at day 30.
-- Mirrors the AbandonmentRecoveryScheduler pattern for Potencial contacts.
ALTER TABLE beworking.leads
    ADD COLUMN IF NOT EXISTS nurture_email_count   INT       NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS last_nurture_email_at TIMESTAMP;
