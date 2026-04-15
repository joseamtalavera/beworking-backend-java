-- Track real billing_method breakdown in reconciliation results
ALTER TABLE beworking.reconciliation_results
  ADD COLUMN IF NOT EXISTS db_stripe INTEGER,
  ADD COLUMN IF NOT EXISTS db_bank_transfer INTEGER;
