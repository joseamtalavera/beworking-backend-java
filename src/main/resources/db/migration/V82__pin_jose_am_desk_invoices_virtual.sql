-- V82: Pin Jose AM Talavera's two MA1O1-5 invoices to virtual_office.
--
-- V81 categorised by product description, so "Alquiler de Mesa MA1O1-5"
-- became 'coworking'. Per admin: Jose occupies a desk but is billed as a
-- Virtual Office customer, so these two invoices (F254747, F254748,
-- €96.80 each, 2025-10-01) should be virtual_office, not coworking.
--
-- Surgical override of just these two rows; runs after V81. Idempotent.

UPDATE beworking.facturas
SET category = 'virtual_office'
WHERE holdedinvoicenum IN ('F254747', 'F254748')
  AND category IS DISTINCT FROM 'virtual_office';
