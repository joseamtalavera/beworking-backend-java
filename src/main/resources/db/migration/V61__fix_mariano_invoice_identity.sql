-- Phase 2 — Mariano Messina Zordan (contact_profiles.id 91687) invoice fix.
--
-- Runs after V60: V60's backfill froze ALL of Mariano's invoices with his
-- CURRENT profile (ARGOREX LABS SL). That's correct for April onward but wrong
-- for the months before he changed his billing details. This migration sets
-- the right frozen identity per period and fixes the one wrong amount.
--
-- Decisions (confirmed with the business owner):
--  - Up to March (8 invoices): bill under his personal name, NIF intentionally
--    left empty (pre-April company details unknown — to be filled in later).
--  - April (GT5714): correct company = ARGOREX LABS SL + NIF B26928598 + the
--    Málaga address.
--  - GT5714 was stored as €15.00 / 0% VAT but he actually paid €18.15 (Stripe),
--    same shape as every other month → total 18.15, iva 21, totaliva 3.15.
--    The other 8 already match Stripe; their amounts are untouched.
--
-- idcliente = 91687 uniquely scopes the rows (idfactura alone collides across
-- cuentas). Idempotent: explicit target values; the amount fix is guarded.

-- ≤ March — personal name, no NIF, no address (pre-April details unknown).
UPDATE beworking.facturas
   SET billing_name        = 'Mariano Messina Zordan',
       billing_tax_id      = NULL,
       billing_tax_id_type = NULL,
       billing_address     = NULL,
       billing_postal_code = NULL,
       billing_city        = NULL,
       billing_province    = NULL,
       billing_country     = NULL,
       billing_snapshot_at  = NOW()
 WHERE idcliente = 91687
   AND idfactura IN (212174, 212375, 212719, 213141, 213535, 4825, 5001, 5438);

-- April GT5714 — correct company identity (ARGOREX LABS SL).
UPDATE beworking.facturas
   SET billing_name        = 'ARGOREX LABS SL',
       billing_tax_id      = 'B26928598',
       billing_tax_id_type = 'eu_vat',
       billing_address     = 'Calle Alejandro Dumas 17 - OFICINAS',
       billing_postal_code = '29004',
       billing_city        = 'Málaga',
       billing_province    = 'Málaga',
       billing_country     = 'Spain',
       billing_vat_percent = 21,
       billing_snapshot_at  = NOW()
 WHERE idcliente = 91687
   AND idfactura = 5714;

-- GT5714 amount — mirror what Stripe charged (€18.15), same shape as the
-- other 8 (net 15.00 + 21% VAT 3.15). Guarded so a re-run is a no-op.
UPDATE beworking.facturas
   SET total    = 18.15,
       iva      = 21,
       totaliva = 3.15
 WHERE idcliente = 91687
   AND idfactura = 5714
   AND total = 15.00;
