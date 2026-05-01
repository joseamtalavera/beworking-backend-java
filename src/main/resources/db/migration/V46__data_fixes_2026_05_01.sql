-- Data fixes from 2026-05-01.
-- Already applied manually via psql on staging (`beworking_staging`) and on
-- prod (`beworking`). Bundled here so the same state can be reproduced in
-- fresh dev DBs and any future environment. All three statements are
-- idempotent — re-running them on an already-fixed DB is a 0-row no-op.

-- ── 1. Remove duplicate MA1_DESK room ──────────────────────────────────
-- The catalog historically had two desk rows: id=711 'MA1-DESKS' (type='Mesa',
-- canonical) and id=705 'MA1_DESK' (type='Aula', no city, no bloqueo refs).
-- The duplicate caused the booking-app and dashboard to render two "MA1 Desks"
-- entries. Drop child rows first because the FKs are NO ACTION.

DELETE FROM beworking.room_amenities
 WHERE room_id IN (SELECT id FROM beworking.rooms WHERE code = 'MA1_DESK');

DELETE FROM beworking.room_images
 WHERE room_id IN (SELECT id FROM beworking.rooms WHERE code = 'MA1_DESK');

DELETE FROM beworking.rooms WHERE code = 'MA1_DESK';

-- ── 2. Link NJM's third desk subscription to MA1O1-9 ───────────────────
-- Subscription id=425 was created without a producto_id (the other two NJM
-- subs id=310→MA1O1-10 and id=313→MA1O1-16 had it set at creation). Without
-- the link, the booking-app counted this desk as available even though NJM
-- had paid for it. Only updates if the row still has producto_id IS NULL,
-- so a manual edit or admin override is preserved.

UPDATE beworking.subscriptions
   SET producto_id = (SELECT id FROM beworking.productos WHERE nombre = 'MA1O1-9'),
       updated_at = NOW()
 WHERE id = 425
   AND producto_id IS NULL
   AND EXISTS (SELECT 1 FROM beworking.productos WHERE nombre = 'MA1O1-9');

-- ── 3. Seed daily desk price on MA1-DESKS ──────────────────────────────
-- BookingService auto-invoicing for one-off desk bookings reads price_day
-- on the parent rooms row (looked up by type + centro). Set it to €10/day
-- only if it's currently NULL — preserves any later admin/SQL change.

UPDATE beworking.rooms
   SET price_day = 10.00
 WHERE code = 'MA1-DESKS'
   AND price_day IS NULL;
