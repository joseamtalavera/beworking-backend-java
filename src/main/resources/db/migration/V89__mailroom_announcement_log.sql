-- Idempotent send log for the one-time Business Address / Mailbox announcement
-- (mail scanning + QR package pickup). Mirrors bekey_announcement_log: one row
-- per emailed address so a re-run only sends to those not yet contacted.
CREATE TABLE IF NOT EXISTS beworking.mailroom_announcement_log (
    email   TEXT PRIMARY KEY,
    sent_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
