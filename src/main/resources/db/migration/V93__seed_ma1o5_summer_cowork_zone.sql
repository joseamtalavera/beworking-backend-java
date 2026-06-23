-- Summer pop-up coworking zone: convert meeting room MA1A5 into a 14-desk
-- coworking space for 2026-07-01 .. 2026-08-31. Reuses the existing MA1A5
-- Akiles door (member group 'MA1A5'); no new Akiles object.
--
-- This migration seeds three things, each guarded + idempotent:
--   1. 14 desk products  MA1O5-1 .. MA1O5-14   (legacy beworking.productos)
--   2. catalog room row  MA1A5-DESKS           (beworking.rooms)
--   3. a full-window blackout bloqueo on the MA1A5 meeting producto so the room
--      stops being bookable as a meeting room while it's a cowork zone.
--
-- The productos / bloqueos tables predate Flyway and may carry NOT-NULL columns
-- the JPA entities don't map. To stay schema-agnostic we CLONE an existing row
-- (%ROWTYPE) and override only the fields we care about, so every other column
-- inherits a known-valid value. The blackout section is wrapped in a non-fatal
-- handler: a failed Flyway migration blocks backend startup, and the blackout is
-- operational data we can also create by hand — it must never crash the deploy.

-- 1) Desk products MA1O5-1 .. MA1O5-14 (clone of MA1O1-1: same tipo + centro 'MA1')
DO $$
DECLARE
    tmpl  beworking.productos%ROWTYPE;
    maxid BIGINT;
    i     INT;
BEGIN
    IF to_regclass('beworking.productos') IS NULL THEN
        RAISE NOTICE 'V93: beworking.productos missing; skipping desk seed';
        RETURN;
    END IF;

    SELECT * INTO tmpl FROM beworking.productos WHERE LOWER(nombre) = 'ma1o1-1' LIMIT 1;
    IF NOT FOUND THEN
        RAISE NOTICE 'V93: no MA1O1-1 template product; skipping MA1O5 seed';
        RETURN;
    END IF;

    SELECT COALESCE(MAX(id), 0) INTO maxid FROM beworking.productos;

    FOR i IN 1..14 LOOP
        IF NOT EXISTS (SELECT 1 FROM beworking.productos WHERE LOWER(nombre) = LOWER('MA1O5-' || i)) THEN
            tmpl.id     := maxid + i;
            tmpl.nombre := 'MA1O5-' || i;
            INSERT INTO beworking.productos SELECT (tmpl).*;
        END IF;
    END LOOP;
END $$;

-- 2) Catalog room MA1A5-DESKS (mirrors the MA1-DESKS seed; capacity 14, €90/mo)
INSERT INTO beworking.rooms (
    code, name, subtitle, description, address, city, postal_code, country, region,
    centro_code, type, status, creation_date, size_sqm, capacity, price_from, price_unit,
    price_hour_min, price_hour_med, price_hour_max, wifi_credentials, sort_order,
    rating_avg, rating_count, instant_booking, tags, hero_image
) VALUES (
    'MA1A5-DESKS',
    'MA1A5 Coworking (verano)',
    'Calle Alejandro Dumas 17, 29004 Málaga · Julio y agosto',
    'Durante julio y agosto convertimos la Sala MA1A5 en una zona de coworking con 14 puestos individuales. Conexión a internet de 600 Mb simétricos, acceso 24/7, aire acondicionado y zona de descanso. Disponible por día o por mes; última fecha disponible 31 de agosto de 2026.',
    'Calle Alejandro Dumas, 17',
    'Málaga',
    '29004',
    'España',
    'Andalucía',
    'MA1',
    'Mesa',
    'Activo',
    CURRENT_DATE,
    45,
    14,
    90.00,
    '/month',
    NULL,
    NULL,
    NULL,
    'WiFi: BeWorking | Password: contactar recepción',
    11,
    4.8,
    0,
    true,
    'Coworking,Mesa individual,Acceso 24h,Verano',
    'https://app.be-working.com/img/MA1A5-0-featured-20240501123909.jpg'
)
ON CONFLICT (code) DO UPDATE SET
    name = EXCLUDED.name,
    subtitle = EXCLUDED.subtitle,
    description = EXCLUDED.description,
    centro_code = EXCLUDED.centro_code,
    type = EXCLUDED.type,
    status = EXCLUDED.status,
    capacity = EXCLUDED.capacity,
    price_from = EXCLUDED.price_from,
    price_unit = EXCLUDED.price_unit,
    sort_order = EXCLUDED.sort_order,
    instant_booking = EXCLUDED.instant_booking,
    tags = EXCLUDED.tags,
    hero_image = EXCLUDED.hero_image;

DO $$
DECLARE
    v_room_id BIGINT;
BEGIN
    SELECT id INTO v_room_id FROM beworking.rooms WHERE code = 'MA1A5-DESKS';
    IF v_room_id IS NULL THEN
        RETURN;
    END IF;

    DELETE FROM beworking.room_amenities WHERE room_id = v_room_id;
    INSERT INTO beworking.room_amenities (room_id, amenity_code) VALUES
        (v_room_id, 'Acceso 24h'),
        (v_room_id, 'Internet 600Mb'),
        (v_room_id, 'Aire acondicionado'),
        (v_room_id, 'Zona de descanso'),
        (v_room_id, 'Soporte 24h'),
        (v_room_id, 'Sin permanencia'),
        (v_room_id, 'Llave digital');
END $$;

-- 3) Blackout: block MA1A5 as a meeting room for the cowork window. Non-fatal:
--    if the clone/insert fails (unexpected legacy constraint), log + continue so
--    the deploy still succeeds; we'll create the block by hand if so.
DO $$
DECLARE
    tmpl       beworking.bloqueos%ROWTYPE;
    v_prod_id  BIGINT;
BEGIN
    IF to_regclass('beworking.bloqueos') IS NULL THEN
        RAISE NOTICE 'V93: beworking.bloqueos missing; skipping A5 blackout';
        RETURN;
    END IF;

    SELECT id INTO v_prod_id FROM beworking.productos WHERE LOWER(nombre) = 'ma1a5' LIMIT 1;
    IF v_prod_id IS NULL THEN
        RAISE NOTICE 'V93: no MA1A5 producto; skipping A5 blackout';
        RETURN;
    END IF;

    -- Already blacked out for this window? (idempotent)
    IF EXISTS (
        SELECT 1 FROM beworking.bloqueos
         WHERE id_producto = v_prod_id
           AND estado = 'Cowork verano'
           AND fecha_ini = TIMESTAMP '2026-07-01 00:00:00'
    ) THEN
        RAISE NOTICE 'V93: A5 blackout already present; nothing to do';
        RETURN;
    END IF;

    -- Clone a real MA1A5 bloqueo so unknown NOT-NULL columns inherit valid values.
    SELECT * INTO tmpl FROM beworking.bloqueos WHERE id_producto = v_prod_id ORDER BY id DESC LIMIT 1;
    IF NOT FOUND THEN
        -- Fall back to any bloqueo as the structural template, then point it at MA1A5.
        SELECT * INTO tmpl FROM beworking.bloqueos ORDER BY id DESC LIMIT 1;
        IF NOT FOUND THEN
            RAISE NOTICE 'V93: no bloqueo template; skipping A5 blackout';
            RETURN;
        END IF;
    END IF;

    tmpl.id            := nextval('beworking.bloqueos_id_seq');
    tmpl.id_reserva    := NULL;
    tmpl.id_cliente    := NULL;
    tmpl.id_producto   := v_prod_id;
    tmpl.fecha_ini     := TIMESTAMP '2026-07-01 00:00:00';
    tmpl.fecha_fin     := TIMESTAMP '2026-09-01 00:00:00';
    tmpl.fin_indefinido:= 0;
    tmpl.tarifa        := NULL;
    tmpl.asistentes    := NULL;
    tmpl.configuracion := NULL;
    tmpl.nota          := 'Conversion cowork verano 2026 (Sala A5 -> mesas)';
    tmpl.estado        := 'Cowork verano';
    tmpl.creacion_fecha:= now();
    tmpl.edicion_fecha := now();

    INSERT INTO beworking.bloqueos SELECT (tmpl).*;
    RAISE NOTICE 'V93: A5 meeting-room blackout created (producto %, 2026-07-01..2026-09-01)', v_prod_id;
EXCEPTION WHEN OTHERS THEN
    RAISE NOTICE 'V93: A5 blackout skipped (non-fatal): %', SQLERRM;
END $$;
