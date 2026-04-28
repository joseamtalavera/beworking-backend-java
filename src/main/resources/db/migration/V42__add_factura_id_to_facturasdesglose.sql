-- Adds a direct link from facturasdesglose to facturas via the unique
-- internal PK (facturas.id), in preparation for retiring the legacy
-- idfacturadesglose -> facturas.idfactura join.
--
-- facturas.idfactura is NOT unique (per-cuenta counters mean PT4767 and
-- GT4767 can both exist). This causes desglose lines to leak across
-- different invoices that share an idfactura value — the cross-talk bug
-- behind PT4767 showing both an old "oficina virtual 15" line and the
-- current MA1A4 booking line.
--
-- This migration is purely additive:
--   - column is NULLABLE (so existing INSERTs without the column keep working)
--   - no FK yet (added in V44 once writes are dual-populated and verified)
--   - no NOT NULL yet (added in V44)
-- V43 will backfill the column. V44 will enforce.

ALTER TABLE beworking.facturasdesglose
    ADD COLUMN IF NOT EXISTS factura_id BIGINT;

CREATE INDEX IF NOT EXISTS idx_facturasdesglose_factura_id
    ON beworking.facturasdesglose (factura_id);
