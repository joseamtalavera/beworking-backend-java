CREATE TABLE IF NOT EXISTS beworking.room_images (
    id          BIGSERIAL PRIMARY KEY,
    room_id     BIGINT       NOT NULL REFERENCES beworking.rooms(id) ON DELETE CASCADE,
    url         VARCHAR(512) NOT NULL,
    caption     VARCHAR(120),
    is_featured BOOLEAN      DEFAULT FALSE,
    sort_order  INTEGER
);

CREATE TABLE IF NOT EXISTS beworking.room_amenities (
    id           BIGSERIAL PRIMARY KEY,
    room_id      BIGINT      NOT NULL REFERENCES beworking.rooms(id) ON DELETE CASCADE,
    amenity_code VARCHAR(64) NOT NULL
);
