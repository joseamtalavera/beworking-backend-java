-- Seed bekey_member_groups and bekey_devices with production Akiles inventory.
-- Captured 2026-04-27 from org BE WORKING (org_3r3zbp3dpxkjkzqv999h).
-- Site: BE WORKING MA1 (site_3rdmkjxehng57zsb7py1).
--
-- bekey_member_groups: permission bundles in Akiles. Every aula group also
-- includes the street door (Acceso Calle MA1), so granting any room group
-- is sufficient for full daily access.
--
-- bekey_devices: physical gadgets (doors). All in MA1.
-- room_id is left NULL here; backfill in a later migration once beworking.rooms
-- ids are stable across environments.

INSERT INTO beworking.bekey_member_groups (akiles_group_id, label, scope, notes) VALUES
    ('mg_3rep7l926cpknf9e3gkh', 'Acceso Calle MA1', 'common', 'Street door only — included in every other group'),
    ('mg_3rejvpx1zcp5zhducul1', 'MA1O1',            'room',   'Oficina 1 — also serves MA1A1 cowork desks'),
    ('mg_3rek38cstua493dbnvf1', 'MA1A2',            'room',   'Aula 2 + street door'),
    ('mg_3rep7k13bl469udlhzeh', 'MA1A3',            'room',   'Aula 3 + street door'),
    ('mg_3rejy2vygcl3syv7ghgh', 'MA1A4',            'room',   'Aula 4 + street door'),
    ('mg_3rejyk8nvl3n5qsajg9h', 'MA1A5',            'room',   'Aula 5 + street door')
ON CONFLICT (akiles_group_id) DO NOTHING;

INSERT INTO beworking.bekey_devices (akiles_gadget_id, akiles_site_id, name, online) VALUES
    ('gad_417968ze3vvtvjbrb4kh', 'site_3rdmkjxehng57zsb7py1', 'Puerta calle', TRUE),
    ('gad_3rdmldcclku8eamsvcgh', 'site_3rdmkjxehng57zsb7py1', 'Aula 2',       TRUE),
    ('gad_3rdnep5b3furtryhpdk1', 'site_3rdmkjxehng57zsb7py1', 'Aula 3',       TRUE),
    ('gad_3rdmuug9ssl5mxspyy4h', 'site_3rdmkjxehng57zsb7py1', 'Aula 4',       TRUE),
    ('gad_3rdn5tbmtatduskrqkkh', 'site_3rdmkjxehng57zsb7py1', 'Aula 5',       TRUE),
    ('gad_3rdmnld19n4jdjrgpuh1', 'site_3rdmkjxehng57zsb7py1', 'Oficina 1',    TRUE)
ON CONFLICT (akiles_gadget_id) DO NOTHING;
