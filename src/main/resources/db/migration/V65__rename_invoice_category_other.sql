-- Rename the invoice category value 'other' to its final name 'extra'.
--
-- V64 wrote 'other' for invoices whose product type didn't map to a known
-- line. The backend now standardises on 'extra' (see InvoiceCategory), so this
-- aligns the historical rows V64 produced.
--
-- Note: the Overview revenue cards classify by the customer's tenant_type,
-- not by this column — facturas.category is kept only as per-invoice metadata.
--
-- Idempotent: re-running matches zero rows.

UPDATE beworking.facturas SET category = 'extra' WHERE category = 'other';
