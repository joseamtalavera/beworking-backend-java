-- V93/V94 failed to create the MA1A5 meeting-room blackout: both nulled
-- id_reserva (and V93 also id_cliente), which violates NOT-NULL constraints on
-- the legacy bloqueos table, so their non-fatal handlers swallowed the error and
-- no block was created — the A5 meeting room stayed bookable in July/August.
--
-- This clones a real MA1A5 bloqueo and overrides ONLY id + dates + estado/nota,
-- keeping every FK (id_reserva, id_cliente, id_centro) intact so no constraint
-- can fail. The overlap check (findOverlapping) ignores those FKs, so sharing a
-- reserva/cliente with the template is harmless — it still blocks the room.
DO $$
DECLARE
    tmpl      beworking.bloqueos%ROWTYPE;
    v_prod_id BIGINT;
BEGIN
    IF to_regclass('beworking.bloqueos') IS NULL THEN RETURN; END IF;

    SELECT id INTO v_prod_id FROM beworking.productos WHERE LOWER(nombre) = 'ma1a5' LIMIT 1;
    IF v_prod_id IS NULL THEN
        RAISE NOTICE 'V95: no MA1A5 producto; skipping blackout';
        RETURN;
    END IF;

    IF EXISTS (
        SELECT 1 FROM beworking.bloqueos
         WHERE id_producto = v_prod_id AND estado = 'Cowork'
           AND fecha_ini = TIMESTAMP '2026-07-01 00:00:00'
    ) THEN
        RAISE NOTICE 'V95: A5 blackout already present';
        RETURN;
    END IF;

    SELECT * INTO tmpl FROM beworking.bloqueos WHERE id_producto = v_prod_id ORDER BY id DESC LIMIT 1;
    IF NOT FOUND THEN
        RAISE WARNING 'V95: no MA1A5 bloqueo to clone; create blackout by hand';
        RETURN;
    END IF;

    tmpl.id            := nextval('beworking.bloqueos_id_seq');
    tmpl.fecha_ini     := TIMESTAMP '2026-07-01 00:00:00';
    tmpl.fecha_fin     := TIMESTAMP '2026-09-01 00:00:00';
    tmpl.estado        := 'Cowork';
    tmpl.nota          := 'Sala A5 reservada para coworking (no disponible como sala)';
    tmpl.creacion_fecha:= now();
    tmpl.edicion_fecha := now();

    INSERT INTO beworking.bloqueos SELECT (tmpl).*;
    RAISE NOTICE 'V95: A5 meeting-room blackout created (producto %)', v_prod_id;
EXCEPTION WHEN OTHERS THEN
    RAISE WARNING 'V95: A5 blackout NOT created: %', SQLERRM;
END $$;
