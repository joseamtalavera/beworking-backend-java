-- Cached snapshot of the DB-vs-Stripe price-discrepancy audit. The scan does
-- one live Stripe call per paid invoice in the last 30 days, which overran the
-- gateway timeout when run synchronously on every dashboard load (504). The
-- daily PriceDiscrepancyAuditScheduler (and the manual "Refrescar" trigger) now
-- write the result here; the dashboard reads this table instantly. One row per
-- run_date, upserted — mirrors beworking.reconciliation_results.
CREATE TABLE IF NOT EXISTS beworking.price_discrepancy_results (
    run_date   DATE PRIMARY KEY,
    count      INTEGER     NOT NULL DEFAULT 0,
    rows       JSONB       NOT NULL DEFAULT '[]'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
