-- Seed script for MA1A1 room with real data from be-working.com
-- Run this script against the beworking database to populate room data

-- First, insert or update the MA1A1 room
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
    'MA1A1',
    'Aula MA1A1',
    'Calle Alejandro Dumas 17, 29004 Málaga',
    'Nuestra Aula/ Sala 1 de Alejandro Dumas es perfecta para reuniones ó entrevistas. Tiene 15 m2 y esta Equipada con conexión internet 600 Mb simétricos, pizarra y mobiliario. Acceso 24 horas / 365 días. Proyector, Pizarra y Llave digital.',
    'Calle Alejandro Dumas, 17',
    'Málaga',
    '29004',
    'España',
    'Andalucía',
    'MA1',
    'Aula',
    'Activo',
    CURRENT_DATE,
    15,
    6,
    5.00,
    '/h',
    5.00,
    7.50,
    10.00,
    'WiFi: BeWorking | Password: contactar recepción',
    1,
    4.8,
    24,
    true,
    'Reuniones,Formación,Entrevistas',
    'https://be-working.com/wp-content/uploads/2025/09/MA1A1-0-featured-20220504133312-1.jpg'
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
    SELECT id INTO v_room_id FROM beworking.rooms WHERE code = 'MA1A1';

    -- Delete existing images and amenities for this room (to refresh)
    DELETE FROM beworking.room_images WHERE room_id = v_room_id;
    DELETE FROM beworking.room_amenities WHERE room_id = v_room_id;

    -- Insert room images from be-working.com
    INSERT INTO beworking.room_images (room_id, url, caption, is_featured, sort_order) VALUES
        (v_room_id, 'https://be-working.com/wp-content/uploads/2025/09/MA1A1-0-featured-20220504133312-1.jpg', 'Vista principal del aula', true, 1),
        (v_room_id, 'https://be-working.com/wp-content/uploads/2025/09/MA1A1-1-20220504133312.jpg', 'Interior del aula', false, 2),
        (v_room_id, 'https://be-working.com/wp-content/uploads/2025/09/MA1A1-2-20220504133312-scaled.jpg', 'Zona de trabajo', false, 3),
        (v_room_id, 'https://be-working.com/wp-content/uploads/2025/09/MA1A1-3-20220504133312.jpg', 'Detalle mobiliario', false, 4),
        (v_room_id, 'https://be-working.com/wp-content/uploads/2025/09/MA1A1-4-20220504133312.jpg', 'Equipamiento', false, 5);

    -- Insert room amenities
    INSERT INTO beworking.room_amenities (room_id, amenity_code) VALUES
        (v_room_id, 'Acceso 24h'),
        (v_room_id, 'Internet 600Mb'),
        (v_room_id, 'Pizarra y papelógrafo'),
        (v_room_id, 'Proyector'),
        (v_room_id, 'Aire acondicionado'),
        (v_room_id, 'Llave digital'),
        (v_room_id, 'Taquilla'),
        (v_room_id, 'Zona de descanso'),
        (v_room_id, 'Soporte 24h'),
        (v_room_id, 'Sin permanencia');
END $$;
