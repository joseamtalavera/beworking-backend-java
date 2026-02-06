-- Seed script for MA1A3 room with real data from be-working.com (MA1A4 page)
-- Run this script against the beworking database to populate room data

-- First, insert or update the MA1A3 room
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
    'MA1A3',
    'Aula MA1A3',
    'Calle Alejandro Dumas 17, 29004 Málaga',
    'Nuestra Aula/ Sala 4 de Alejandro Dumas es perfecta para reuniones, eventos, formaciones ó entrevistas. Tiene 45 m2 y cuenta con luz natural y zona exterior. Equipada con conexión internet 600 Mb simétricos, pizarra y mobiliario. Acceso 24 horas / 365 días. Proyector, Pizarra y Llave digital.',
    'Calle Alejandro Dumas, 17',
    'Málaga',
    '29004',
    'España',
    'Andalucía',
    'MA1',
    'Aula',
    'Activo',
    CURRENT_DATE,
    45,
    6,
    30.00,
    '/h',
    30.00,
    35.00,
    40.00,
    'WiFi: BeWorking | Password: contactar recepción',
    3,
    4.8,
    24,
    true,
    'Reuniones,Eventos,Formación,Entrevistas',
    'https://be-working.com/wp-content/uploads/2025/09/MA1A4-0-featured-20220505082555-1.jpg'
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

-- Get the room ID for inserting related data
DO $$
DECLARE
    v_room_id BIGINT;
BEGIN
    SELECT id INTO v_room_id FROM beworking.rooms WHERE code = 'MA1A3';

    -- Delete existing images and amenities for this room (to refresh)
    DELETE FROM beworking.room_images WHERE room_id = v_room_id;
    DELETE FROM beworking.room_amenities WHERE room_id = v_room_id;

    -- Insert room images from be-working.com
    INSERT INTO beworking.room_images (room_id, url, caption, is_featured, sort_order) VALUES
        (v_room_id, 'https://be-working.com/wp-content/uploads/2025/09/MA1A4-0-featured-20220505082555-1.jpg', 'Vista principal del aula', true, 1),
        (v_room_id, 'https://be-working.com/wp-content/uploads/2025/09/MA1A4-1-20220505082555.jpg', 'Interior del aula', false, 2),
        (v_room_id, 'https://be-working.com/wp-content/uploads/2025/09/MA1A4-2-20220505082555.jpg', 'Zona de trabajo', false, 3),
        (v_room_id, 'https://be-working.com/wp-content/uploads/2025/09/MA1A4-3-20220505082555.jpg', 'Detalle mobiliario', false, 4),
        (v_room_id, 'https://be-working.com/wp-content/uploads/2025/09/MA1A4-4-20220505082555.jpg', 'Equipamiento', false, 5);

    -- Insert room amenities
    INSERT INTO beworking.room_amenities (room_id, amenity_code) VALUES
        (v_room_id, 'Acceso 24h'),
        (v_room_id, 'Internet 600Mb'),
        (v_room_id, 'Pizarra y papelógrafo'),
        (v_room_id, 'Proyector'),
        (v_room_id, 'Aire acondicionado'),
        (v_room_id, 'Llave digital'),
        (v_room_id, 'Sin permanencia'),
        (v_room_id, 'Escaner e impresora'),
        (v_room_id, 'Soporte 24h'),
        (v_room_id, 'Zona de descanso'),
        (v_room_id, 'Alarma');
END $$;
