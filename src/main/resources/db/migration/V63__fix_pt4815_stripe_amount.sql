-- PT4815 (factura id 16911, cliente 1772646644898 — Roman Suarez Gonzalez).
--
-- Stripe charged €15.00 GROSS (amount_paid, source of truth) but the DB stored
-- €18.15. Mirror Stripe: gross €15.00 with the invoice's locked 21% VAT ⇒
-- net €12.40 + €2.60 VAT = €15.00. VAT going forward is already fixed for this
-- customer (next month), so the rate is left at 21; only this historical row
-- is brought in line with what was actually paid.
-- (facturas.total = GROSS, totaliva = VAT amount, net = total - totaliva.)
--
-- Also collapses a duplicate breakdown line: 29517 ("oficina virtual 15")
-- duplicated 31857 ("Oficina Virtual"), both €15.00. Keep 31857, rescaled to
-- the new net (€12.40); drop 29517.
--
-- Idempotent: every statement guarded on the pre-fix values.

UPDATE beworking.facturas
   SET total = 15.00,
       totaliva = 2.60
 WHERE id = 16911
   AND holdedinvoicenum = 'PT4815'
   AND total = 18.15;

DELETE FROM beworking.facturasdesglose
 WHERE id = 29517
   AND idfacturadesglose = 4815;

UPDATE beworking.facturasdesglose
   SET precioundesglose = 12.40,
       totaldesglose = 12.40
 WHERE id = 31857
   AND idfacturadesglose = 4815
   AND totaldesglose = 15.0000;
