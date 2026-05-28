-- DB facturas that Stripe has already settled-without-payment (status = void or
-- uncollectible) but the DB row still reads Pendiente. These should be filtered
-- OUT of the Unpaid bucket (the customer won't pay them — Stripe closed the
-- invoice) and surfaced as a separate Deviation so admin can clean up the DB.
ALTER TABLE beworking.reconciliation_results
    ADD COLUMN IF NOT EXISTS stripe_closed_db_pending JSONB NOT NULL DEFAULT '[]'::jsonb;
