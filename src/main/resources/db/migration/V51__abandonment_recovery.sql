-- V51: Cart-abandonment recovery scaffolding (2026-05-05)
-- Adds tracking so we can send a one-off recovery email to anyone who
-- triggered Stripe checkout but never completed payment. We deliberately do
-- NOT bulk-flip Activo→Abandono here — only contacts whose status is already
-- 'Pendiente Pago' qualify. Future signups will be flagged automatically by
-- RegisterService when the SetupIntent fires without a paid subscription.

ALTER TABLE beworking.contact_profiles
  ADD COLUMN IF NOT EXISTS abandonment_email_sent_at TIMESTAMP NULL;
