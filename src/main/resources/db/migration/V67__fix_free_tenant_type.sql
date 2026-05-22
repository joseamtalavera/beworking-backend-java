-- Three customers were typed 'Usuario Free' but their only invoices are a
-- meeting-room booking (MA1A3) and day passes — they are not free users.
-- Retype them so their revenue lands in the right Overview card.
--
--   1775720981014  Blanca Fernandez  -> Usuario Aulas   (meeting room)
--   1775837807822  Flavia Mihai      -> Usuario Nómada  (coworking)
--   1776176480947  Nikolay           -> Usuario Nómada  (coworking)
--
-- Idempotent: only updates rows still typed 'Usuario Free'.

UPDATE beworking.contact_profiles
   SET tenant_type = 'Usuario Aulas'
 WHERE id = 1775720981014
   AND tenant_type = 'Usuario Free';

UPDATE beworking.contact_profiles
   SET tenant_type = 'Usuario Nómada'
 WHERE id IN (1775837807822, 1776176480947)
   AND tenant_type = 'Usuario Free';
