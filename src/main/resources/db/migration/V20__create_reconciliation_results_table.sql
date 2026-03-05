CREATE TABLE IF NOT EXISTS beworking.reconciliation_results (
    id                      BIGSERIAL PRIMARY KEY,
    run_date                DATE        NOT NULL,
    account                 VARCHAR(10) NOT NULL,  -- 'GT' or 'PT'
    db_active               INTEGER     NOT NULL DEFAULT 0,
    stripe_active           INTEGER     NOT NULL DEFAULT 0,
    stripe_past_due         INTEGER     NOT NULL DEFAULT 0,
    past_due_amount         NUMERIC(12,2) NOT NULL DEFAULT 0,
    missing_invoice_count   INTEGER     NOT NULL DEFAULT 0,
    missing_invoices        JSONB,
    past_due_subs           JSONB,
    created_at              TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (run_date, account)
);

CREATE INDEX IF NOT EXISTS idx_reconciliation_run_date
    ON beworking.reconciliation_results (run_date DESC);
