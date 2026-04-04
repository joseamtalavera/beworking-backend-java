ALTER TABLE beworking.reconciliation_results
    ADD COLUMN IF NOT EXISTS db_only_subs JSONB DEFAULT '[]'::jsonb,
    ADD COLUMN IF NOT EXISTS stripe_only_subs JSONB DEFAULT '[]'::jsonb;
