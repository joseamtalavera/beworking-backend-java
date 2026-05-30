-- V83: Mark the 11 stale 2025 Pendiente invoices as Pagado (admin decision).
--
-- These desk/aula invoices (Sep-Oct 2025, PT book) sat unpaid with
-- category=NULL and were surfaced during the #228 reconciliation review.
-- Admin has decided to consider them paid (collected offline / written off
-- as settled) rather than chase 8-month-old debt. Flipping to Pagado clears
-- them from the Pendiente bucket; revenue is unchanged (Pendiente already
-- counts toward revenue).
--
-- Scoped by (holdedinvoicenum, total) so only these exact rows flip — each
-- of these F-numbers also has a separate same-number row at a different
-- amount that must NOT be touched. Idempotent.

UPDATE beworking.facturas
SET estado = 'Pagado'
WHERE estado = 'Pendiente'
  AND holdedcuenta = 'PT'
  AND (holdedinvoicenum, total) IN (
    ('F254760', 108.90), ('F254762', 108.90), ('F254746', 108.90),
    ('F254752', 108.90), ('F254756', 108.90), ('F254768', 108.90),
    ('F254742', 108.90),
    ('F254747', 96.80),  ('F254748', 96.80),
    ('PT4315', 24.20),   ('F254669', 12.10)
  );
