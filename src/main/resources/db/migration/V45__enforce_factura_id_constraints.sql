-- Phase 4: enforce NOT NULL + FK on facturasdesglose.factura_id.
--
-- Production was cleaned manually before this migration ran:
--   - 22 reconcilable Service-Fee orphans backfilled (idfacturadesglose
--     accidentally stored the factura's internal PK; verified by amount
--     match: line €15 + 21%/20% VAT = factura total €18.15/€18.00).
--   - 59 unrecoverable orphans archived to facturasdesglose_orphans_archive
--     and deleted (booking-line coincidences, monthly aggregates whose
--     parent factura never existed, etc.).
--
-- This migration is idempotent so it no-ops in prod (constraints already
-- present) and applies cleanly elsewhere. Non-prod environments: any
-- residual NULL rows are archived to facturasdesglose_orphans_archive
-- before the NOT NULL constraint is set.

CREATE TABLE IF NOT EXISTS beworking.facturasdesglose_orphans_archive
    AS SELECT * FROM beworking.facturasdesglose WHERE FALSE;

INSERT INTO beworking.facturasdesglose_orphans_archive
SELECT * FROM beworking.facturasdesglose WHERE factura_id IS NULL;

DELETE FROM beworking.facturasdesglose WHERE factura_id IS NULL;

DO $$
BEGIN
    IF (SELECT is_nullable
        FROM information_schema.columns
        WHERE table_schema = 'beworking'
          AND table_name = 'facturasdesglose'
          AND column_name = 'factura_id') = 'YES' THEN
        ALTER TABLE beworking.facturasdesglose
            ALTER COLUMN factura_id SET NOT NULL;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'fk_facturasdesglose_factura_id'
    ) THEN
        ALTER TABLE beworking.facturasdesglose
            ADD CONSTRAINT fk_facturasdesglose_factura_id
            FOREIGN KEY (factura_id)
            REFERENCES beworking.facturas(id)
            ON DELETE RESTRICT;
    END IF;
END $$;
