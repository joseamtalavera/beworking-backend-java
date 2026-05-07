-- Reconciliation ignore list for orphan Stripe invoices.
-- Used when a user has been removed from the DB but their paid Stripe
-- invoice keeps surfacing in DailyReconciliationScheduler.findMissingInvoices.
-- Each entry must include a reason so future-Jose remembers why.

CREATE TABLE IF NOT EXISTS beworking.reconciliation_ignored_invoices (
    stripe_invoice_id VARCHAR(255) PRIMARY KEY,
    reason TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);

-- Seed: jose.am.talavera@proton.me's BeWorking Basic invoice. The contact
-- profile was deleted from the DB; the Stripe sub was cancelled, but the
-- April-paid invoice still appears in the daily reconciliation report.
INSERT INTO beworking.reconciliation_ignored_invoices (stripe_invoice_id, reason)
VALUES (
    'in_1TKKz3IGBPwEtf1iVr9mNtKi',
    'jose.am.talavera@proton.me removed from DB; sub cancelled. Apr 2026 paid invoice (€18.15) is orphaned.'
)
ON CONFLICT (stripe_invoice_id) DO NOTHING;
