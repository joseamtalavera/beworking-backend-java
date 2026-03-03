-- Reset identity sequences for room_amenities and room_images.
-- Previous bulk inserts left the sequences behind the actual max id,
-- causing duplicate-key errors on subsequent INSERTs.

SELECT setval(
    pg_get_serial_sequence('beworking.room_amenities', 'id'),
    COALESCE((SELECT MAX(id) FROM beworking.room_amenities), 0) + 1,
    false
);

SELECT setval(
    pg_get_serial_sequence('beworking.room_images', 'id'),
    COALESCE((SELECT MAX(id) FROM beworking.room_images), 0) + 1,
    false
);
