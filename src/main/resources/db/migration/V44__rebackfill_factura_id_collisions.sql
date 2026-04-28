-- V43's heuristic mis-assigned desglose rows in collision groups (e.g.
-- idfactura=4767 had both rows go to the older factura instead of being
-- partitioned). Root-cause analysis pointed at a numeric-precision quirk
-- in `total / (1 + iva/100)` — V44 uses exact integer subtraction
-- (`total - totaliva`) which avoids division entirely.
--
-- Strategy:
--   1. Reset factura_id = NULL for every desglose row whose idfacturadesglose
--      points at more than one factura (collision groups only).
--   2. Re-run the heuristic with the corrected expected_base.
--   3. Catch-all unmatched leftovers to the most recent factura.

-- ─── Pass 1: reset collision-group rows ─────────────────────────────────
UPDATE beworking.facturasdesglose fd
SET factura_id = NULL
WHERE fd.idfacturadesglose IN (
        SELECT idfactura FROM beworking.facturas
        WHERE idfactura IS NOT NULL
        GROUP BY idfactura HAVING COUNT(*) > 1
);

-- ─── Pass 2: corrected heuristic ────────────────────────────────────────
DO $$
DECLARE
    coll_idfactura INTEGER;
    factura_rec    RECORD;
    desglose_rec   RECORD;
    accumulated    NUMERIC;
    expected_base  NUMERIC;
BEGIN
    FOR coll_idfactura IN
        SELECT idfactura FROM beworking.facturas
        WHERE idfactura IS NOT NULL
        GROUP BY idfactura HAVING COUNT(*) > 1
    LOOP
        FOR factura_rec IN
            SELECT id, total, totaliva, COALESCE(iva, 0) AS iva_pct
            FROM beworking.facturas
            WHERE idfactura = coll_idfactura
            ORDER BY creacionfecha NULLS LAST, id
        LOOP
            accumulated := 0;
            -- Exact subtraction avoids floating-point quirks. Fall back to
            -- division-based derivation only when totaliva is missing.
            expected_base := CASE
                WHEN factura_rec.totaliva IS NOT NULL
                    THEN COALESCE(factura_rec.total, 0) - factura_rec.totaliva
                WHEN factura_rec.iva_pct > 0
                    THEN COALESCE(factura_rec.total, 0) / (1 + factura_rec.iva_pct::numeric / 100)
                ELSE COALESCE(factura_rec.total, 0)
            END;

            FOR desglose_rec IN
                SELECT id, COALESCE(totaldesglose, 0) AS line_total
                FROM beworking.facturasdesglose
                WHERE idfacturadesglose = coll_idfactura
                  AND factura_id IS NULL
                ORDER BY id
            LOOP
                EXIT WHEN accumulated >= expected_base;

                UPDATE beworking.facturasdesglose
                SET factura_id = factura_rec.id
                WHERE id = desglose_rec.id;

                accumulated := accumulated + desglose_rec.line_total;
            END LOOP;
        END LOOP;

        -- Catch-all: any rows that didn't fit into any factura's expected
        -- base (over-committed or unmatched) attribute to the most recent
        -- factura so they don't end up NULL.
        UPDATE beworking.facturasdesglose
        SET factura_id = (
                SELECT id FROM beworking.facturas
                WHERE idfactura = coll_idfactura
                ORDER BY creacionfecha DESC NULLS LAST, id DESC
                LIMIT 1
        )
        WHERE idfacturadesglose = coll_idfactura
          AND factura_id IS NULL;
    END LOOP;
END;
$$;
