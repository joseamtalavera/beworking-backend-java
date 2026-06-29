-- V96: persist subscription invoice-number reservations.
--
-- For Stripe subs the first invoice number is reserved at invoice.created
-- (CuentaService bumps the counter, e.g. PT4958) and pushed onto the Stripe
-- invoice via modify(number=...). The local factura was then supposed to reuse
-- that number — but the ONLY link was a round-trip through Stripe's
-- invoice.number field. When invoice.finalized arrived before our modify was
-- reflected, stripeInvoiceNumber came back empty, the backend fell through to
-- CuentaService and minted a SECOND number one above the reserved-and-burned
-- one. Result: Stripe shows PT4958, BeWorking PT4959, and PT4958 is a permanent
-- gap in the sequential numbering (legal issue for ES invoices). Observed on
-- MORE FRESH FRUITS SPAIN SL (2026-06-29).
--
-- This table lets the reservation be looked up deterministically by
-- stripe_invoice_id at factura-creation time, so the reserved number is reused
-- exactly — no race, no divergence, no gap.

CREATE TABLE IF NOT EXISTS beworking.invoice_number_reservations (
    stripe_invoice_id  VARCHAR(255) PRIMARY KEY,
    invoice_number     VARCHAR(50)  NOT NULL,
    cuenta             VARCHAR(10),
    created_at         TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
