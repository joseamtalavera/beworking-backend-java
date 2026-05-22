-- Three Virtual Office customers had a blank tenant_type, so their invoices
-- showed up as untyped on the Overview revenue cards (which group revenue by
-- the customer's tenant_type). All three only ever hold "Oficina Virtual" /
-- "BeWorking Basic" invoices, so they are Virtual Office customers.
--
--   91883          Alisa Shevchuk
--   91715          Daniil Sereda
--   1777912097453  Patrick Ladewig
--
-- Setting their type routes that revenue into the Virtual Office card.
--
-- Idempotent: only updates rows whose tenant_type is still blank.

UPDATE beworking.contact_profiles
   SET tenant_type = 'Usuario Virtual'
 WHERE id IN (91883, 91715, 1777912097453)
   AND (tenant_type IS NULL OR TRIM(tenant_type) = '');
