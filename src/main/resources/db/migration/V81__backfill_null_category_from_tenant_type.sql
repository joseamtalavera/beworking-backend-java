-- V81: Backfill facturas.category for rows the V72 sub-origin backfill missed.
--
-- 11 PT-book invoices (€992.20) carried category=NULL, so they were invisible
-- in the dashboard Pendiente bucket (counts only category IN
-- ('virtual_office','coworking')).
--
-- Category must reflect the PRODUCT SOLD, not the customer's tenant_type:
-- Jose AM Talavera is Usuario Virtual but two of these invoices are desk
-- rentals ("Alquiler de Mesa MA1O1-5") → coworking, not virtual_office.
-- So we derive category from the line description, which names the product:
--   "Alquiler de Mesa"          → coworking
--   "Alquiler de Aula"          → meeting_room
--   "Oficina Virtual" / virtual → virtual_office
-- Rows whose description matches none of these are left NULL (don't guess).
-- Idempotent (only touches NULL category).

UPDATE beworking.facturas
SET category = CASE
    WHEN descripcion ILIKE '%aula%'                                   THEN 'meeting_room'
    WHEN descripcion ILIKE '%mesa%' OR descripcion ILIKE '%coworking%' THEN 'coworking'
    WHEN descripcion ILIKE '%oficina virtual%'
      OR descripcion ILIKE '%virtual office%'                          THEN 'virtual_office'
  END
WHERE category IS NULL
  AND (
        descripcion ILIKE '%aula%'
     OR descripcion ILIKE '%mesa%'
     OR descripcion ILIKE '%coworking%'
     OR descripcion ILIKE '%oficina virtual%'
     OR descripcion ILIKE '%virtual office%'
  );
