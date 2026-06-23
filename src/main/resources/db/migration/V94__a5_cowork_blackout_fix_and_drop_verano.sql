-- A5 cowork follow-ups:
--   1. Drop all "verano"/seasonal wording — Desk 2 (MA1A5 coworking) is permanent;
--      it just stays blocked outside its bookable window.
--   2. Create a ROBUST meeting-room blackout for MA1A5 (2026-07-01 .. 2026-09-01).
--      V93's attempt set id_cliente = NULL, which hit a NOT-NULL constraint and was
--      swallowed by its non-fatal handler — so no block was created and the A5
--      meeting room stayed bookable. Here we CLONE a real MA1A5 bloqueo so every
--      NOT-NULL column (incl. id_cliente, id_centro) inherits a valid value, and
--      only override the block fields.

-- 1) Rename the MA1A5-DESKS room: no seasonal wording.
UPDATE beworking.rooms
   SET name = 'MA1A5 Coworking',
       subtitle = 'Calle Alejandro Dumas 17, 29004 Málaga',
       description = 'Zona de coworking en la Sala MA1A5 con 14 puestos individuales. Conexión a internet de 600 Mb simétricos, acceso 24/7, aire acondicionado y zona de descanso.',
       tags = 'Coworking,Mesa individual,Acceso 24h'
 WHERE code = 'MA1A5-DESKS';

-- 2) Normalise any blackout status V93 may have created.
UPDATE beworking.bloqueos SET estado = 'Cowork' WHERE estado = 'Cowork verano';

-- 3) Robust MA1A5 meeting-room blackout.
DO $$
DECLARE
    tmpl      beworking.bloqueos%ROWTYPE;
    v_prod_id BIGINT;
BEGIN
    IF to_regclass('beworking.bloqueos') IS NULL THEN RETURN; END IF;

    SELECT id INTO v_prod_id FROM beworking.productos WHERE LOWER(nombre) = 'ma1a5' LIMIT 1;
    IF v_prod_id IS NULL THEN
        RAISE NOTICE 'V94: no MA1A5 producto; skipping blackout';
        RETURN;
    END IF;

    -- Already blocked for this window? (idempotent)
    IF EXISTS (
        SELECT 1 FROM beworking.bloqueos
         WHERE id_producto = v_prod_id
           AND estado = 'Cowork'
           AND fecha_ini = TIMESTAMP '2026-07-01 00:00:00'
    ) THEN
        RAISE NOTICE 'V94: A5 blackout already present';
        RETURN;
    END IF;

    -- Clone a real MA1A5 bloqueo (keeps id_cliente / id_centro valid).
    SELECT * INTO tmpl FROM beworking.bloqueos WHERE id_producto = v_prod_id ORDER BY id DESC LIMIT 1;
    IF NOT FOUND THEN
        SELECT * INTO tmpl FROM beworking.bloqueos ORDER BY id DESC LIMIT 1;
        IF NOT FOUND THEN
            RAISE NOTICE 'V94: no bloqueo template; skipping blackout';
            RETURN;
        END IF;
        tmpl.id_producto := v_prod_id;
    END IF;

    tmpl.id             := nextval('beworking.bloqueos_id_seq');
    tmpl.id_reserva     := NULL;
    tmpl.fecha_ini      := TIMESTAMP '2026-07-01 00:00:00';
    tmpl.fecha_fin      := TIMESTAMP '2026-09-01 00:00:00';
    tmpl.fin_indefinido := 0;
    tmpl.tarifa         := NULL;
    tmpl.asistentes     := NULL;
    tmpl.configuracion  := NULL;
    tmpl.nota           := 'Sala A5 reservada para coworking (no disponible como sala)';
    tmpl.estado         := 'Cowork';
    tmpl.creacion_fecha := now();
    tmpl.edicion_fecha  := now();

    INSERT INTO beworking.bloqueos SELECT (tmpl).*;
    RAISE NOTICE 'V94: A5 meeting-room blackout created (producto %)', v_prod_id;
EXCEPTION WHEN OTHERS THEN
    -- Never block startup; surface the reason so we can create it by hand.
    RAISE WARNING 'V94: A5 blackout NOT created: %', SQLERRM;
END $$;
