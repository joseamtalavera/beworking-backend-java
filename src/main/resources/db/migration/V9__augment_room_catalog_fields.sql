ALTER TABLE beworking.rooms
    ADD COLUMN IF NOT EXISTS centro_code VARCHAR(32);

ALTER TABLE beworking.rooms
    ADD COLUMN IF NOT EXISTS creation_date DATE;

ALTER TABLE beworking.rooms
    ADD COLUMN IF NOT EXISTS price_unit VARCHAR(16);

ALTER TABLE beworking.rooms
    ADD COLUMN IF NOT EXISTS instant_booking BOOLEAN DEFAULT FALSE;

ALTER TABLE beworking.rooms
    ADD COLUMN IF NOT EXISTS tags VARCHAR(255);

ALTER TABLE beworking.rooms
    ADD COLUMN IF NOT EXISTS hero_image VARCHAR(512);
