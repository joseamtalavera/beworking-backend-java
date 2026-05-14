-- Tracking columns for the daily past-due reminder cron.
--
-- subscriptions.past_due_email_count + last_past_due_email_at — cap customer
-- dunning emails at 3 sends per past-due cycle (day 1 / day 3 / day 7).
-- past_due_period_start lets us reset the counter when a sub goes past-due
-- again after recovering, so a new cycle starts fresh.
ALTER TABLE beworking.subscriptions
    ADD COLUMN IF NOT EXISTS past_due_email_count   INT       NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS last_past_due_email_at TIMESTAMP,
    ADD COLUMN IF NOT EXISTS past_due_period_start  TIMESTAMP;

-- One-off invoices (meeting rooms / desks) follow the same 3-email cadence
-- once they're 24h past-due (Pendiente + creacionfecha < NOW() - 1 day).
ALTER TABLE beworking.facturas
    ADD COLUMN IF NOT EXISTS dunning_email_count   INT       NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS last_dunning_email_at TIMESTAMP;
