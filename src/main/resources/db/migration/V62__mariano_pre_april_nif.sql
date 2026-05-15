-- Mariano Messina Zordan (contact 91687) — backfill the pre-April NIF.
--
-- V61 left billing_tax_id empty on the 8 ≤March invoices because the
-- pre-April details were unknown at the time. The business owner has now
-- supplied his personal Spanish NIF: 60059273V (8 digits + letter ⇒ es_nif,
-- an individual — distinct from the April-onward company CIF B26928598).
-- Billing name on these rows stays "Mariano Messina Zordan" (set by V61).
--
-- Idempotent: only fills rows that are still blank.

UPDATE beworking.facturas
   SET billing_tax_id      = '60059273V',
       billing_tax_id_type = 'es_nif'
 WHERE idcliente = 91687
   AND idfactura IN (212174, 212375, 212719, 213141, 213535, 4825, 5001, 5438)
   AND COALESCE(billing_tax_id, '') = '';
