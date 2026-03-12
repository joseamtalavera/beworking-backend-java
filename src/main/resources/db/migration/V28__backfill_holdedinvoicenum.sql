-- Backfill holdedinvoicenum for existing invoices that are missing it.
-- Assigns cuenta-based sequential numbers (PT4211, GT4751, etc.) ordered
-- by creation date within each cuenta, then advances the cuenta counters.

-- Step 1: Default id_cuenta to PT (id=3) for any facturas still missing it
UPDATE beworking.facturas
SET id_cuenta = (SELECT id FROM beworking.cuentas WHERE codigo = 'PT')
WHERE id_cuenta IS NULL AND holdedinvoicenum IS NULL;

-- Step 2: Assign sequential invoice numbers per cuenta
DO $$
DECLARE
    r RECORD;
    cuenta_rec RECORD;
    seq INTEGER;
BEGIN
    -- Process each cuenta that has facturas needing backfill
    FOR cuenta_rec IN
        SELECT c.id, c.prefijo_factura, c.numero_secuencial
        FROM beworking.cuentas c
        WHERE EXISTS (
            SELECT 1 FROM beworking.facturas f
            WHERE f.id_cuenta = c.id AND f.holdedinvoicenum IS NULL
        )
    LOOP
        seq := cuenta_rec.numero_secuencial;

        -- Assign numbers in chronological order
        FOR r IN
            SELECT f.id
            FROM beworking.facturas f
            WHERE f.id_cuenta = cuenta_rec.id
              AND f.holdedinvoicenum IS NULL
            ORDER BY f.creacionfecha ASC, f.id ASC
        LOOP
            seq := seq + 1;
            UPDATE beworking.facturas
            SET holdedinvoicenum = cuenta_rec.prefijo_factura || seq,
                holdedcuenta = COALESCE(holdedcuenta, cuenta_rec.prefijo_factura)
            WHERE id = r.id;
        END LOOP;

        -- Advance the cuenta counter to the new high-water mark
        UPDATE beworking.cuentas
        SET numero_secuencial = seq, updated_at = CURRENT_TIMESTAMP
        WHERE id = cuenta_rec.id;
    END LOOP;
END $$;
