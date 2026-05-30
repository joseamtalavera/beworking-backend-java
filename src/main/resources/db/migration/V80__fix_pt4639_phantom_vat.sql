-- V80: Correct PT4639 to match what Stripe actually charged (issue #220).
--
-- Between Light & Shadow S.L. — first invoice 2026-04-08, a pre-VAT-overhaul
-- sub. Stripe invoice in_1TJxQdIGBPwEtf1imFS9bEFx charged €15.00 with "no tax
-- rate applied", but the DB row was created with the with-VAT expected total
-- (€18.15 = 15 × 1.21, iva=21, totaliva=3.15). The May overhaul (2026-05-03)
-- rewrote the affected MAY invoices to match Stripe but this April first-invoice
-- was missed.
--
-- The desglose line (id=31280) is already the correct €15.00 base, so only the
-- factura header needs aligning. Same "rewrite to what Stripe charged, no refund"
-- treatment the overhaul applied to its cohort.
--
-- Idempotent: guarded on the exact broken shape, so re-runs are no-ops.

UPDATE beworking.facturas
SET total = 15.00,
    iva = 0,
    totaliva = 0.00
WHERE holdedinvoicenum = 'PT4639'
  AND total = 18.15
  AND iva = 21;
