-- Reconcile bloqueos that are linked to an invoice (facturasdesglose) but still
-- carry a non-invoiced status. This fixes historical data created via manual invoice
-- paths or Holded-synced invoices that did not update bloqueo estado.
UPDATE beworking.bloqueos b
SET estado = 'Invoiced'
WHERE b.estado IN ('Booked', 'Reservado', 'Pendiente', 'Confirmado')
  AND EXISTS (
      SELECT 1
      FROM beworking.facturasdesglose fd
      WHERE fd.idbloqueovinculado = b.id
  );
