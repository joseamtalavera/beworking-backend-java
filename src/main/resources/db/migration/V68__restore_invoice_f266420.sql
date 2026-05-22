-- Invoice 15336 (David Sánchez Molina — MUDANZAS Y GUARDAMUEBLES LA SEDA,
-- contact 86691; Feb 2026 Oficina Virtual, EUR 15) was renumbered into the
-- GT series as GT4963, but the customer holds the original invoice F266420.
-- Restore the customer-facing number to F266420.
--
-- Only holdedinvoicenum is changed. idfactura (4963), id_cuenta and the
-- facturasdesglose join key are deliberately left intact: bumping idfactura
-- into the 266xxx range would pollute the GT sequence (next GT invoice would
-- jump to 266421+).
--
-- Idempotent: guarded on the current value.

UPDATE beworking.facturas
   SET holdedinvoicenum = 'F266420'
 WHERE id = 15336
   AND holdedinvoicenum = 'GT4963';
