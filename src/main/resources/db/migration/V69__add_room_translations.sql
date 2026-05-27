-- V69: Add English translations for room descriptions and subtitles.
-- Public booking app picks description_en / subtitle_en when locale = 'en';
-- falls back to the original Spanish values (or i18n default) when blank.

ALTER TABLE beworking.rooms
    ADD COLUMN IF NOT EXISTS description_en VARCHAR(2000),
    ADD COLUMN IF NOT EXISTS subtitle_en    VARCHAR(255);

-- Backfill EN content for currently seeded rooms. Idempotent: only writes when
-- the EN column is still NULL so a re-run never clobbers admin edits.

UPDATE beworking.rooms
SET description_en = 'Our Room 1 at Alejandro Dumas is perfect for meetings or interviews. 15 m² equipped with 600 Mb symmetric internet, whiteboard and furniture. 24/7 access, 365 days a year. Projector, whiteboard and digital key.',
    subtitle_en    = COALESCE(subtitle_en, subtitle)
WHERE code = 'MA1A1' AND description_en IS NULL;

UPDATE beworking.rooms
SET description_en = 'Our Room 3 at Alejandro Dumas is perfect for meetings, training, events or interviews. 45 m² with 600 Mb symmetric internet, whiteboard, furniture and projector. Lounge area, digital key and alarm. 24/7 access, 365 days a year.',
    subtitle_en    = COALESCE(subtitle_en, subtitle)
WHERE code = 'MA1A3' AND description_en IS NULL;

UPDATE beworking.rooms
SET description_en = 'Our Room 4 at Alejandro Dumas is perfect for meetings, events, training or interviews. 45 m² with natural light and an outdoor area. Equipped with 600 Mb symmetric internet, whiteboard and furniture. 24/7 access, 365 days a year. Projector, whiteboard and digital key.',
    subtitle_en    = COALESCE(subtitle_en, subtitle)
WHERE code = 'MA1A4' AND description_en IS NULL;

UPDATE beworking.rooms
SET description_en = 'Our Room 5 at Alejandro Dumas is perfect for meetings, events, training or interviews. 45 m² with 600 Mb symmetric internet, whiteboard and projector. Lounge area, digital key and alarm. 24/7 access, 365 days a year.',
    subtitle_en    = COALESCE(subtitle_en, subtitle)
WHERE code = 'MA1A5' AND description_en IS NULL;

UPDATE beworking.rooms
SET description_en = 'Shared coworking space with 16 individual desks, 24/7 access and digital key. Equipped with 600 Mb symmetric internet, scanner, printer and lounge area. Available by the day or by the month.',
    subtitle_en    = COALESCE(subtitle_en, subtitle)
WHERE code IN ('MA1-DESK', 'MA1DESK', 'MA1DESKS', 'MA1O1') AND description_en IS NULL;
