-- V70: MA1A2 was not seeded via the V0xx scripts, so V69 skipped it.
-- Add the EN translation here. Idempotent: only writes when EN is still NULL.

UPDATE beworking.rooms
SET description_en = 'Our Room 2 at Alejandro Dumas is perfect for meetings, training sessions, events or interviews. 45 m² fully equipped with 600 Mb symmetric internet, whiteboard, furniture and projector. Digital key, lounge area, 24/7 access (365 days), alarm and air conditioning. Bright, outward-facing and comfortable.',
    subtitle_en    = COALESCE(subtitle_en, subtitle)
WHERE code = 'MA1A2' AND description_en IS NULL;
