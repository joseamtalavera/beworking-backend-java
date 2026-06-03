-- V85: Delete the failed "extra invoice" attempts for contact 91806
-- (GRUPO GESTION DE LLAMADAS L&O SLU).
--
-- Background (2026-06-03): an admin tried to issue a €18.90 extra invoice.
-- Two bugs collided: (1) the charge created an orphan Stripe customer instead
-- of reusing the contact's subscription customer, so the charge never settled;
-- (2) creditInvoice() ran without a transaction, so the V78 guard blocked the
-- estado='Rectificado' UPDATE *after* the credit-note row had already committed
-- — leaving the original Pendiente and letting a retry mint a second RECT note.
-- Net result: 3 unpaid originals (PT5157/GT6078/GT6079) + 4 stray credit notes,
-- none ever paid. Voided in Stripe; delete the rows outright here.
--
-- The €15 OV subscription invoice GT5925 is unrelated and is NOT touched.
-- Scoped by idcliente + holdedinvoicenum so re-runs and other tenants are safe.
-- DELETE does not trip the V78 estado trigger (INSERT/UPDATE only).
-- facturasdesglose.factura_id (V45 FK) → delete child rows first.

DELETE FROM beworking.facturasdesglose
WHERE factura_id IN (
    SELECT id FROM beworking.facturas
    WHERE idcliente = 91806
      AND holdedinvoicenum IN ('PT5157','RECT-PT5157','RECT-PT5157-2',
                               'GT6078','RECT-GT6078','RECT-GT6078-2','GT6079')
);

DELETE FROM beworking.facturas
WHERE idcliente = 91806
  AND holdedinvoicenum IN ('PT5157','RECT-PT5157','RECT-PT5157-2',
                           'GT6078','RECT-GT6078','RECT-GT6078-2','GT6079');
