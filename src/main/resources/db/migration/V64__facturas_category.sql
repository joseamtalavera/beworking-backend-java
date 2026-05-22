-- Phase 2 — invoice category.
--
-- The Overview "Meeting Rooms / Coworking" stat cards classified invoices by
-- string-matching room codes (MA1A#, "aula", "sala", ...) in the description.
-- Booking-flow invoices carry the code, so they classified; manually-typed
-- invoices ("RESERVA 6 JULIO HASTA 9 JULIO", "ALQUILER DE ESPACIO") have no
-- code and fell into no card at all — silently undercounting every card.
--
-- This adds a machine-readable category. New invoices get it set at creation
-- (auto-derived from the product type, or chosen by the admin for manual
-- invoices). Here we backfill existing invoices from the linked product type.
--
-- Values: meeting_room | coworking | virtual_office | other
-- Product tipo -> category: Aula -> meeting_room, Mesa -> coworking,
--                           Oficina Virtual -> virtual_office, else -> other.
--
-- Idempotent: ADD COLUMN IF NOT EXISTS + guarded backfill (category IS NULL).

ALTER TABLE beworking.facturas
    ADD COLUMN IF NOT EXISTS category VARCHAR(32);

-- Backfill from the product behind each invoice line. factura_id is the clean
-- FK to facturas.id (see V42-V45). MODE() picks the dominant product type
-- when a single invoice mixes line types.
WITH inv_type AS (
    SELECT fd.factura_id AS factura_id,
           MODE() WITHIN GROUP (ORDER BY p.tipo) AS tipo
      FROM beworking.facturasdesglose fd
      JOIN beworking.bloqueos  b ON b.id = fd.idbloqueovinculado
      JOIN beworking.productos p ON p.id = b.id_producto
     WHERE fd.factura_id IS NOT NULL
       AND p.tipo IS NOT NULL
     GROUP BY fd.factura_id
)
UPDATE beworking.facturas f
   SET category = CASE inv_type.tipo
           WHEN 'Aula'            THEN 'meeting_room'
           WHEN 'Mesa'            THEN 'coworking'
           WHEN 'Oficina Virtual' THEN 'virtual_office'
           ELSE 'other'
       END
  FROM inv_type
 WHERE f.id = inv_type.factura_id
   AND f.category IS NULL;
