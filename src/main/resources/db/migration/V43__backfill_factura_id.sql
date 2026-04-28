-- Backfill facturasdesglose.factura_id (added in V42).
--
-- Two-pass strategy:
--   1. Bulk UPDATE for unambiguous rows (idfacturadesglose matches exactly
--      one factura).
--   2. PL/pgSQL pass for collision groups: assign desglose rows to facturas
--      in chronological + base-amount-matching order.
--
-- Rows whose idfacturadesglose matches no factura at all (the historical
-- "orphan desglose" rows, currently ~81) keep factura_id = NULL. They are
-- handled separately in Phase 5 cleanup.

-- ─── Pass 1: unambiguous matches ────────────────────────────────────────
UPDATE beworking.facturasdesglose fd
SET factura_id = (
        SELECT f.id FROM beworking.facturas f
        WHERE f.idfactura = fd.idfacturadesglose
)
WHERE fd.factura_id IS NULL
  AND fd.idfacturadesglose IS NOT NULL
  AND (
        SELECT COUNT(*) FROM beworking.facturas f
        WHERE f.idfactura = fd.idfacturadesglose
  ) = 1;

-- ─── Pass 2: collision groups ───────────────────────────────────────────
-- For each idfactura value with multiple facturas, walk desglose rows in
-- id order and facturas in (creacionfecha, id) order. Each factura
-- "consumes" desglose rows until its expected base (total / (1 + iva/100))
-- is reached, then the next factura takes over.
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
            SELECT id, total, COALESCE(iva, 0) AS iva_pct
            FROM beworking.facturas
            WHERE idfactura = coll_idfactura
            ORDER BY creacionfecha NULLS LAST, id
        LOOP
            accumulated   := 0;
            expected_base := CASE
                WHEN factura_rec.iva_pct = 0 THEN COALESCE(factura_rec.total, 0)
                ELSE COALESCE(factura_rec.total, 0) / (1 + factura_rec.iva_pct / 100.0)
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

        -- Any leftover desglose rows in this collision group (over-committed
        -- or unmatched) attribute to the most recent factura as a final
        -- catch-all so they're not silently NULL.
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
