-- Seed script for MA1-DESKS room (aggregated desk product for booking)
-- Run this script against the beworking database to populate desk room data

INSERT INTO beworking.rooms (
    code,
    name,
    subtitle,
    description,
    address,
    city,
    postal_code,
    country,
    region,
    centro_code,
    type,
    status,
    creation_date,
    size_sqm,
    capacity,
    price_from,
    price_unit,
    price_hour_min,
    price_hour_med,
    price_hour_max,
    wifi_credentials,
    sort_order,
    rating_avg,
    rating_count,
    instant_booking,
    tags,
    hero_image
) VALUES (
    'MA1-DESKS',
    'MA1 Desks',
    'Calle Alejandro Dumas 17, 29004 Málaga',
    'Nuestros puestos de trabajo individuales en el espacio de coworking de Alejandro Dumas ofrecen un entorno profesional y productivo. Cada mesa cuenta con conexión a internet de 600 Mb simétricos, acceso 24/7 los 365 días del año, y taquilla personal. Incluye uso de zonas comunes, cocina y terraza.',
    'Calle Alejandro Dumas, 17',
    'Málaga',
    '29004',
    'España',
    'Andalucía',
    'MA1',
    'Mesa',
    'Activo',
    CURRENT_DATE,
    NULL,
    16,
    90.00,
    '/month',
    NULL,
    NULL,
    NULL,
    'WiFi: BeWorking | Password: contactar recepción',
    10,
    4.8,
    0,
    true,
    'Coworking,Mesa individual,Acceso 24h',
    'https://app.be-working.com/img/MA1O1-1-0-featured-20220512103754.jpg'
)
ON CONFLICT (code) DO UPDATE SET
    name = EXCLUDED.name,
    subtitle = EXCLUDED.subtitle,
    description = EXCLUDED.description,
    address = EXCLUDED.address,
    city = EXCLUDED.city,
    postal_code = EXCLUDED.postal_code,
    country = EXCLUDED.country,
    region = EXCLUDED.region,
    centro_code = EXCLUDED.centro_code,
    type = EXCLUDED.type,
    status = EXCLUDED.status,
    size_sqm = EXCLUDED.size_sqm,
    capacity = EXCLUDED.capacity,
    price_from = EXCLUDED.price_from,
    price_unit = EXCLUDED.price_unit,
    price_hour_min = EXCLUDED.price_hour_min,
    price_hour_med = EXCLUDED.price_hour_med,
    price_hour_max = EXCLUDED.price_hour_max,
    wifi_credentials = EXCLUDED.wifi_credentials,
    sort_order = EXCLUDED.sort_order,
    rating_avg = EXCLUDED.rating_avg,
    rating_count = EXCLUDED.rating_count,
    instant_booking = EXCLUDED.instant_booking,
    tags = EXCLUDED.tags,
    hero_image = EXCLUDED.hero_image;

DO $$
DECLARE
    v_room_id BIGINT;
BEGIN
    SELECT id INTO v_room_id FROM beworking.rooms WHERE code = 'MA1-DESKS';

    DELETE FROM beworking.room_images WHERE room_id = v_room_id;
    DELETE FROM beworking.room_amenities WHERE room_id = v_room_id;

    INSERT INTO beworking.room_images (room_id, url, caption, is_featured, sort_order) VALUES
        (v_room_id, 'https://app.be-working.com/img/MA1O1-1-0-featured-20220512103754.jpg', 'Puesto de trabajo coworking', true, 1),
        (v_room_id, 'https://app.be-working.com/img/MA1O1-1-1-20220512103754.jpg', 'Espacio de coworking', false, 2),
        (v_room_id, 'https://app.be-working.com/img/MA1O1-1-2-20220512103754.jpg', 'Zona de trabajo compartida', false, 3),
        (v_room_id, 'https://app.be-working.com/img/MA1O1-1-3-20220512103754.jpg', 'Detalle del puesto', false, 4),
        (v_room_id, 'https://app.be-working.com/img/MA1O1-1-4-20220512103754.jpg', 'Vista general', false, 5);

    INSERT INTO beworking.room_amenities (room_id, amenity_code) VALUES
        (v_room_id, 'Acceso 24h'),
        (v_room_id, 'Internet 600Mb'),
        (v_room_id, 'Taquilla personal'),
        (v_room_id, 'Aire acondicionado'),
        (v_room_id, 'Cocina compartida'),
        (v_room_id, 'Terraza'),
        (v_room_id, 'Zona de descanso'),
        (v_room_id, 'Soporte 24h'),
        (v_room_id, 'Sin permanencia'),
        (v_room_id, 'Llave digital');
END $$;
