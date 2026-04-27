-- Fix swapped data between MA1A3 and MA1A4 rooms.
-- V002 seeded MA1A3 with MA1A4's images/description and vice versa.

-- Fix MA1A3: should be Sala 3 with MA1A3 images
UPDATE beworking.rooms SET
    description = 'Nuestra Aula/ Sala 3 de Alejandro Dumas es perfecta para reuniones, formaciones, eventos o entrevistas. Tiene 45 m2 y cuenta con conexión internet 600 Mb simétricos, pizarra, mobiliario y proyector. Zona de descanso, llave digital y alarma. Acceso 24 horas / 365 días.',
    hero_image = 'https://app.be-working.com/img/MA1A3-0-featured-20220504145833.jpg'
WHERE code = 'MA1A3';

-- Fix MA1A4: should be Sala 4 with MA1A4 images
UPDATE beworking.rooms SET
    description = 'Nuestra Aula/ Sala 4 de Alejandro Dumas es perfecta para reuniones, eventos, formaciones ó entrevistas. Tiene 45 m2 y cuenta con luz natural y zona exterior. Equipada con conexión internet 600 Mb simétricos, pizarra y mobiliario. Acceso 24 horas / 365 días. Proyector, Pizarra y Llave digital.',
    hero_image = 'https://be-working.com/wp-content/uploads/2025/09/MA1A4-0-featured-20220505082555-1.jpg'
WHERE code = 'MA1A4';

-- Fix MA1A3 images
DO $$
DECLARE
    v_room_id BIGINT;
BEGIN
    SELECT id INTO v_room_id FROM beworking.rooms WHERE code = 'MA1A3';
    DELETE FROM beworking.room_images WHERE room_id = v_room_id;
    INSERT INTO beworking.room_images (room_id, url, caption, is_featured, sort_order) VALUES
        (v_room_id, 'https://app.be-working.com/img/MA1A3-0-featured-20220504145833.jpg', 'Vista principal del aula', true, 1),
        (v_room_id, 'https://app.be-working.com/img/MA1A3-1-20220504145833.jpg', 'Interior del aula', false, 2),
        (v_room_id, 'https://app.be-working.com/img/MA1A3-2-20220504145833.jpg', 'Zona de trabajo', false, 3),
        (v_room_id, 'https://app.be-working.com/img/MA1A3-3-20220504145833.jpg', 'Detalle mobiliario', false, 4),
        (v_room_id, 'https://app.be-working.com/img/MA1A3-4-20220504145833.jpg', 'Equipamiento', false, 5);
END $$;

-- Fix MA1A4 images
DO $$
DECLARE
    v_room_id BIGINT;
BEGIN
    SELECT id INTO v_room_id FROM beworking.rooms WHERE code = 'MA1A4';
    DELETE FROM beworking.room_images WHERE room_id = v_room_id;
    INSERT INTO beworking.room_images (room_id, url, caption, is_featured, sort_order) VALUES
        (v_room_id, 'https://be-working.com/wp-content/uploads/2025/09/MA1A4-0-featured-20220505082555-1.jpg', 'Vista principal del aula', true, 1),
        (v_room_id, 'https://be-working.com/wp-content/uploads/2025/09/MA1A4-1-20220505082555.jpg', 'Interior del aula', false, 2),
        (v_room_id, 'https://be-working.com/wp-content/uploads/2025/09/MA1A4-2-20220505082555.jpg', 'Zona de trabajo', false, 3),
        (v_room_id, 'https://be-working.com/wp-content/uploads/2025/09/MA1A4-3-20220505082555.jpg', 'Detalle mobiliario', false, 4),
        (v_room_id, 'https://be-working.com/wp-content/uploads/2025/09/MA1A4-4-20220505082555.jpg', 'Equipamiento', false, 5);
END $$;
