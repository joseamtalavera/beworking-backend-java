-- Direction (b) of the Invoices > Deviation metric:
-- invoices Stripe says are paid, but the local factura row is still Pendiente
-- (e.g. webhook landed late, sub got cancelled in the gap, factura never
-- flipped to Pagado — Claudia GT5733 was the canonical case).
ALTER TABLE beworking.reconciliation_results
    ADD COLUMN IF NOT EXISTS stripe_paid_db_pending JSONB NOT NULL DEFAULT '[]'::jsonb;
