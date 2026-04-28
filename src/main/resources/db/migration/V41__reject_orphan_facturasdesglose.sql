-- Reject INSERTs into facturasdesglose whose idfacturadesglose has no matching
-- facturas row. Pre-staged orphan rows have appeared historically (oficina virtual,
-- Service Fees) and silently attached to monthly batch invoices once the cuenta
-- counter caught up — inflating PDF totals while facturas.total stayed correct.
--
-- The trigger only guards new inserts. Existing orphan rows (~81 as of V41) and
-- the ~12k legacy rows where idfactura is out of sync with holdedinvoicenum are
-- left untouched; they're a data-migration artifact, not actively harmful.

CREATE OR REPLACE FUNCTION beworking.reject_orphan_desglose()
RETURNS TRIGGER AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM beworking.facturas WHERE idfactura = NEW.idfacturadesglose
    ) THEN
        RAISE EXCEPTION
            'facturasdesglose.idfacturadesglose=% has no matching facturas row (orphan insert blocked)',
            NEW.idfacturadesglose
        USING ERRCODE = 'foreign_key_violation';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_reject_orphan_desglose ON beworking.facturasdesglose;

CREATE TRIGGER trg_reject_orphan_desglose
    BEFORE INSERT ON beworking.facturasdesglose
    FOR EACH ROW
    EXECUTE FUNCTION beworking.reject_orphan_desglose();
