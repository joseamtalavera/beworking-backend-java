ALTER TABLE beworking.subscriptions
  ADD COLUMN IF NOT EXISTS billing_method VARCHAR(20) NOT NULL DEFAULT 'stripe',
  ADD COLUMN IF NOT EXISTS last_invoiced_month VARCHAR(7);

ALTER TABLE beworking.subscriptions
  ALTER COLUMN stripe_subscription_id DROP NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS idx_subscriptions_last_invoiced
  ON beworking.subscriptions (contact_id, cuenta, last_invoiced_month)
  WHERE billing_method = 'bank_transfer';
