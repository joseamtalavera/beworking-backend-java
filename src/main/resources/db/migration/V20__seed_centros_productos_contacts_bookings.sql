-- V20: Seed centros, productos, contact_profiles, reservas, bloqueos, and facturas
-- Ensures the dashboard has real configuration and sample data

-- ═══════════════════════════════════════════════════
-- 1. CENTROS (idempotent — skip if already present)
-- ═══════════════════════════════════════════════════
INSERT INTO beworking.centros (id, codigo, nombre, direccion, localidad, provincia)
VALUES
  (1, 'MA1', 'MALAGA DUMAS', 'Calle Alejandro Dumas 17', 'Málaga', 'Málaga'),
  (8, 'MAOV', 'Oficina Virtual', 'Calle Alejandro Dumas 17', 'Málaga', 'Málaga')
ON CONFLICT (id) DO NOTHING;

-- ═══════════════════════════════════════════════════
-- 2. PRODUCTOS — meeting rooms + desks
-- ═══════════════════════════════════════════════════
INSERT INTO beworking.productos (id, nombre, tipo, centro, capacidad, estado, tarifahoraocupmin, tarifahoraocupmed, tarifahoraocupmax, tarifadia, tarifamesm1, tarifadesde)
VALUES
  -- Meeting rooms
  (1,  'MA1A1', 'Aula', 'MA1',  6,  'Activo', 15, 20, 25, 60, 300, 15),
  (2,  'MA1A2', 'Aula', 'MA1',  8,  'Activo', 18, 24, 30, 75, 400, 18),
  (3,  'MA1A3', 'Aula', 'MA1', 12,  'Activo', 22, 28, 35, 90, 500, 22),
  (4,  'MA1A4', 'Aula', 'MA1', 16,  'Activo', 28, 35, 42, 120, 650, 28),
  (5,  'MA1A5', 'Aula', 'MA1', 20,  'Activo', 35, 42, 50, 150, 800, 35),
  -- Desks
  (11, 'MA1O1',  'Mesa', 'MA1', 1, 'Activo', 0, 0, 0, 10, 90, 10),
  (12, 'MA1O2',  'Mesa', 'MA1', 1, 'Activo', 0, 0, 0, 10, 90, 10),
  (13, 'MA1O3',  'Mesa', 'MA1', 1, 'Activo', 0, 0, 0, 10, 90, 10),
  (14, 'MA1O4',  'Mesa', 'MA1', 1, 'Activo', 0, 0, 0, 10, 90, 10),
  (15, 'MA1O5',  'Mesa', 'MA1', 1, 'Activo', 0, 0, 0, 10, 90, 10),
  (16, 'MA1O6',  'Mesa', 'MA1', 1, 'Activo', 0, 0, 0, 10, 90, 10),
  (17, 'MA1O7',  'Mesa', 'MA1', 1, 'Activo', 0, 0, 0, 10, 90, 10),
  (18, 'MA1O8',  'Mesa', 'MA1', 1, 'Activo', 0, 0, 0, 10, 90, 10),
  (19, 'MA1O9',  'Mesa', 'MA1', 1, 'Activo', 0, 0, 0, 10, 90, 10),
  (20, 'MA1O10', 'Mesa', 'MA1', 1, 'Activo', 0, 0, 0, 10, 90, 10)
ON CONFLICT (id) DO NOTHING;

-- ═══════════════════════════════════════════════════
-- 3. CONTACT PROFILES — sample companies
-- ═══════════════════════════════════════════════════
INSERT INTO beworking.contact_profiles
  (id, name, contact_name, email_primary, phone_primary, tenant_type, status, is_active, billing_name, billing_tax_id, billing_address, billing_city, billing_country, billing_postal_code, center_id, created_at, status_changed_at)
VALUES
  (1001, 'TechStart SL',       'Carlos Ruiz',    'carlos@techstart.es',    '+34 612 345 001', 'Usuario Aulas',   'Activo', true, 'TechStart SL',       'B12345678', 'Calle Larios 5',           'Málaga', 'España', '29001', 1, '2025-06-15T10:00:00', '2025-06-15T10:00:00'),
  (1002, 'Digital Nomads Co',   'Laura Gómez',    'laura@digitalnomads.co', '+34 612 345 002', 'Usuario Virtual', 'Activo', true, 'Digital Nomads Co',   'B23456789', 'Paseo del Parque 12',       'Málaga', 'España', '29015', 1, '2025-08-01T09:00:00', '2025-08-01T09:00:00'),
  (1003, 'CreativeHub',         'María López',    'maria@creativehub.es',   '+34 612 345 003', 'Usuario Mesa',    'Activo', true, 'CreativeHub SL',      'B34567890', 'Alameda Principal 30',      'Málaga', 'España', '29001', 1, '2025-09-10T11:00:00', '2025-09-10T11:00:00'),
  (1004, 'LegalExperts',        'Pedro Martín',   'pedro@legalexperts.es',  '+34 612 345 004', 'Usuario Aulas',   'Activo', true, 'LegalExperts Abogados','B45678901', 'Plaza de la Constitución 8','Málaga', 'España', '29005', 1, '2025-10-20T08:00:00', '2025-10-20T08:00:00'),
  (1005, 'GreenEnergy SA',      'Ana Fernández',  'ana@greenenergy.es',     '+34 612 345 005', 'Usuario Virtual', 'Activo', true, 'GreenEnergy SA',      'A56789012', 'Av. de Andalucía 22',       'Málaga', 'España', '29006', 1, '2025-11-05T10:30:00', '2025-11-05T10:30:00'),
  (1006, 'MarketPro Agency',    'Jorge Sánchez',  'jorge@marketpro.es',     '+34 612 345 006', 'Usuario Aulas',   'Activo', true, 'MarketPro Agency SL', 'B67890123', 'Calle Nueva 15',            'Málaga', 'España', '29002', 1, '2026-01-12T09:30:00', '2026-01-12T09:30:00')
ON CONFLICT (id) DO NOTHING;

-- ═══════════════════════════════════════════════════
-- 4. RESERVAS — parent bookings
-- ═══════════════════════════════════════════════════
INSERT INTO beworking.reservas
  (id, id_cliente, id_centro, id_producto, tipo_reserva, reserva_desde, reserva_hasta, reserva_hora_desde, reserva_hora_hasta, tarifa, asistentes, estado, creacion_fecha)
VALUES
  -- Meeting room bookings
  (5001, 1001, 1, 1, 'Por Horas', '2026-03-03', '2026-03-03', '09:00', '11:00', 20, 4, 'Pagado',    '2026-02-28T14:00:00'),
  (5002, 1004, 1, 2, 'Por Horas', '2026-03-04', '2026-03-04', '10:00', '13:00', 24, 6, 'Pagado',    '2026-03-01T09:00:00'),
  (5003, 1006, 1, 3, 'Por Horas', '2026-03-04', '2026-03-04', '14:00', '17:00', 28, 10,'Pendiente', '2026-03-02T11:00:00'),
  (5004, 1001, 1, 1, 'Por Horas', '2026-03-05', '2026-03-05', '09:00', '12:00', 20, 5, 'Pagado',    '2026-03-03T10:00:00'),
  (5005, 1004, 1, 4, 'Por Horas', '2026-03-06', '2026-03-06', '10:00', '12:00', 35, 14,'Pendiente', '2026-03-04T08:00:00'),
  (5006, 1002, 1, 1, 'Por Horas', '2026-03-07', '2026-03-07', '11:00', '13:00', 20, 3, 'Pagado',    '2026-03-05T15:00:00'),
  (5007, 1006, 1, 2, 'Por Horas', '2026-03-10', '2026-03-10', '09:00', '11:00', 24, 7, 'Pagado',    '2026-03-07T10:00:00'),
  (5008, 1001, 1, 3, 'Por Horas', '2026-03-11', '2026-03-11', '14:00', '18:00', 28, 8, 'Pendiente', '2026-03-08T09:00:00'),
  (5009, 1003, 1, 1, 'Por Horas', '2026-03-12', '2026-03-12', '10:00', '12:00', 20, 4, 'Pagado',    '2026-03-10T11:00:00'),
  (5010, 1004, 1, 5, 'Por Horas', '2026-03-13', '2026-03-13', '09:00', '13:00', 42, 18,'Pagado',    '2026-03-11T14:00:00'),
  -- Desk bookings
  (5011, 1003, 1, 11, 'Mesa', '2026-03-01', '2026-03-31', '09:00', '18:00', 90, 1, 'Pagado',    '2026-02-25T10:00:00'),
  (5012, 1002, 1, 12, 'Mesa', '2026-03-01', '2026-03-31', '09:00', '18:00', 90, 1, 'Pagado',    '2026-02-26T11:00:00'),
  (5013, 1005, 1, 13, 'Mesa', '2026-03-01', '2026-03-31', '09:00', '18:00', 90, 1, 'Pendiente', '2026-02-27T09:00:00'),
  -- Past month bookings (Feb 2026)
  (5014, 1001, 1, 1, 'Por Horas', '2026-02-10', '2026-02-10', '10:00', '12:00', 20, 4, 'Pagado', '2026-02-05T10:00:00'),
  (5015, 1004, 1, 2, 'Por Horas', '2026-02-12', '2026-02-12', '14:00', '16:00', 24, 6, 'Pagado', '2026-02-08T14:00:00'),
  (5016, 1006, 1, 3, 'Por Horas', '2026-02-18', '2026-02-18', '09:00', '12:00', 28, 9, 'Pagado', '2026-02-14T09:00:00'),
  -- Jan 2026
  (5017, 1001, 1, 2, 'Por Horas', '2026-01-15', '2026-01-15', '10:00', '13:00', 24, 5, 'Pagado', '2026-01-10T10:00:00'),
  (5018, 1002, 1, 1, 'Por Horas', '2026-01-22', '2026-01-22', '09:00', '11:00', 20, 3, 'Pagado', '2026-01-18T09:00:00')
ON CONFLICT (id) DO NOTHING;

-- ═══════════════════════════════════════════════════
-- 5. BLOQUEOS — individual time blocks
-- ═══════════════════════════════════════════════════
INSERT INTO beworking.bloqueos
  (id, id_reserva, id_cliente, id_centro, id_producto, fecha_ini, fecha_fin, tarifa, asistentes, estado, creacion_fecha)
VALUES
  -- March 2026 meeting rooms
  (9001, 5001, 1001, 1, 1, '2026-03-03 09:00:00', '2026-03-03 11:00:00', 20, 4, 'Pagado',    '2026-02-28T14:00:00'),
  (9002, 5002, 1004, 1, 2, '2026-03-04 10:00:00', '2026-03-04 13:00:00', 24, 6, 'Pagado',    '2026-03-01T09:00:00'),
  (9003, 5003, 1006, 1, 3, '2026-03-04 14:00:00', '2026-03-04 17:00:00', 28, 10,'Pendiente', '2026-03-02T11:00:00'),
  (9004, 5004, 1001, 1, 1, '2026-03-05 09:00:00', '2026-03-05 12:00:00', 20, 5, 'Pagado',    '2026-03-03T10:00:00'),
  (9005, 5005, 1004, 1, 4, '2026-03-06 10:00:00', '2026-03-06 12:00:00', 35, 14,'Pendiente', '2026-03-04T08:00:00'),
  (9006, 5006, 1002, 1, 1, '2026-03-07 11:00:00', '2026-03-07 13:00:00', 20, 3, 'Pagado',    '2026-03-05T15:00:00'),
  (9007, 5007, 1006, 1, 2, '2026-03-10 09:00:00', '2026-03-10 11:00:00', 24, 7, 'Pagado',    '2026-03-07T10:00:00'),
  (9008, 5008, 1001, 1, 3, '2026-03-11 14:00:00', '2026-03-11 18:00:00', 28, 8, 'Pendiente', '2026-03-08T09:00:00'),
  (9009, 5009, 1003, 1, 1, '2026-03-12 10:00:00', '2026-03-12 12:00:00', 20, 4, 'Pagado',    '2026-03-10T11:00:00'),
  (9010, 5010, 1004, 1, 5, '2026-03-13 09:00:00', '2026-03-13 13:00:00', 42, 18,'Pagado',    '2026-03-11T14:00:00'),
  -- March desks (full-day blocks for each working day)
  (9011, 5011, 1003, 1, 11, '2026-03-03 09:00:00', '2026-03-03 18:00:00', 10, 1, 'Pagado',    '2026-02-25T10:00:00'),
  (9012, 5011, 1003, 1, 11, '2026-03-04 09:00:00', '2026-03-04 18:00:00', 10, 1, 'Pagado',    '2026-02-25T10:00:00'),
  (9013, 5011, 1003, 1, 11, '2026-03-05 09:00:00', '2026-03-05 18:00:00', 10, 1, 'Pagado',    '2026-02-25T10:00:00'),
  (9014, 5011, 1003, 1, 11, '2026-03-06 09:00:00', '2026-03-06 18:00:00', 10, 1, 'Pagado',    '2026-02-25T10:00:00'),
  (9015, 5011, 1003, 1, 11, '2026-03-07 09:00:00', '2026-03-07 18:00:00', 10, 1, 'Pagado',    '2026-02-25T10:00:00'),
  (9016, 5012, 1002, 1, 12, '2026-03-03 09:00:00', '2026-03-03 18:00:00', 10, 1, 'Pagado',    '2026-02-26T11:00:00'),
  (9017, 5012, 1002, 1, 12, '2026-03-04 09:00:00', '2026-03-04 18:00:00', 10, 1, 'Pagado',    '2026-02-26T11:00:00'),
  (9018, 5013, 1005, 1, 13, '2026-03-03 09:00:00', '2026-03-03 18:00:00', 10, 1, 'Pendiente', '2026-02-27T09:00:00'),
  (9019, 5013, 1005, 1, 13, '2026-03-04 09:00:00', '2026-03-04 18:00:00', 10, 1, 'Pendiente', '2026-02-27T09:00:00'),
  -- Feb 2026 meeting rooms
  (9020, 5014, 1001, 1, 1, '2026-02-10 10:00:00', '2026-02-10 12:00:00', 20, 4, 'Pagado', '2026-02-05T10:00:00'),
  (9021, 5015, 1004, 1, 2, '2026-02-12 14:00:00', '2026-02-12 16:00:00', 24, 6, 'Pagado', '2026-02-08T14:00:00'),
  (9022, 5016, 1006, 1, 3, '2026-02-18 09:00:00', '2026-02-18 12:00:00', 28, 9, 'Pagado', '2026-02-14T09:00:00'),
  -- Jan 2026
  (9023, 5017, 1001, 1, 2, '2026-01-15 10:00:00', '2026-01-15 13:00:00', 24, 5, 'Pagado', '2026-01-10T10:00:00'),
  (9024, 5018, 1002, 1, 1, '2026-01-22 09:00:00', '2026-01-22 11:00:00', 20, 3, 'Pagado', '2026-01-18T09:00:00')
ON CONFLICT (id) DO NOTHING;

-- ═══════════════════════════════════════════════════
-- 6. FACTURAS — invoices (paid + pending + overdue)
-- ═══════════════════════════════════════════════════
INSERT INTO beworking.facturas
  (id, idfactura, idcliente, idcentro, descripcion, total, iva, totaliva, estado, creacionfecha, id_cuenta)
VALUES
  -- March 2026 — Paid
  (8001, 5001, 1001, 1, 'Sala MA1A1 — 03/03 09:00-11:00', 40.00,  21, 48.40,  'Pagado',    '2026-03-03T11:30:00', 1),
  (8002, 5002, 1004, 1, 'Sala MA1A2 — 04/03 10:00-13:00', 72.00,  21, 87.12,  'Pagado',    '2026-03-04T13:30:00', 1),
  (8003, 5006, 1002, 1, 'Sala MA1A1 — 07/03 11:00-13:00', 40.00,  21, 48.40,  'Pagado',    '2026-03-07T13:30:00', 1),
  (8004, 5007, 1006, 1, 'Sala MA1A2 — 10/03 09:00-11:00', 48.00,  21, 58.08,  'Pagado',    '2026-03-10T11:30:00', 1),
  (8005, 5009, 1003, 1, 'Sala MA1A1 — 12/03 10:00-12:00', 40.00,  21, 48.40,  'Pagado',    '2026-03-12T12:30:00', 1),
  (8006, 5010, 1004, 1, 'Sala MA1A5 — 13/03 09:00-13:00', 168.00, 21, 203.28, 'Pagado',    '2026-03-13T13:30:00', 1),
  -- March 2026 — Pending
  (8007, 5003, 1006, 1, 'Sala MA1A3 — 04/03 14:00-17:00', 84.00,  21, 101.64, 'Pendiente', '2026-03-04T17:30:00', 1),
  (8008, 5005, 1004, 1, 'Sala MA1A4 — 06/03 10:00-12:00', 70.00,  21, 84.70,  'Pendiente', '2026-03-06T12:30:00', 1),
  (8009, 5008, 1001, 1, 'Sala MA1A3 — 11/03 14:00-18:00', 112.00, 21, 135.52, 'Pendiente', '2026-03-11T18:30:00', 1),
  -- Desk subscriptions March
  (8010, 5011, 1003, 1, 'Escritorio MA1O1 — Marzo 2026',  90.00,  21, 108.90, 'Pagado',    '2026-03-01T08:00:00', 1),
  (8011, 5012, 1002, 1, 'Escritorio MA1O2 — Marzo 2026',  90.00,  21, 108.90, 'Pagado',    '2026-03-01T08:00:00', 1),
  (8012, 5013, 1005, 1, 'Escritorio MA1O3 — Marzo 2026',  90.00,  21, 108.90, 'Pendiente', '2026-03-01T08:00:00', 1),
  -- February 2026 — Paid
  (8013, 5014, 1001, 1, 'Sala MA1A1 — 10/02 10:00-12:00', 40.00,  21, 48.40,  'Pagado',    '2026-02-10T12:30:00', 1),
  (8014, 5015, 1004, 1, 'Sala MA1A2 — 12/02 14:00-16:00', 48.00,  21, 58.08,  'Pagado',    '2026-02-12T16:30:00', 1),
  (8015, 5016, 1006, 1, 'Sala MA1A3 — 18/02 09:00-12:00', 84.00,  21, 101.64, 'Pagado',    '2026-02-18T12:30:00', 1),
  -- January 2026 — Paid
  (8016, 5017, 1001, 1, 'Sala MA1A2 — 15/01 10:00-13:00', 72.00,  21, 87.12,  'Pagado',    '2026-01-15T13:30:00', 1),
  (8017, 5018, 1002, 1, 'Sala MA1A1 — 22/01 09:00-11:00', 40.00,  21, 48.40,  'Pagado',    '2026-01-22T11:30:00', 1),
  -- Overdue invoices
  (8018, NULL, 1005, 1, 'Escritorio MA1O3 — Febrero 2026', 90.00,  21, 108.90, 'Vencido',   '2026-02-01T08:00:00', 1),
  (8019, NULL, 1005, 1, 'Escritorio MA1O3 — Enero 2026',   90.00,  21, 108.90, 'Vencido',   '2026-01-01T08:00:00', 1)
ON CONFLICT (id) DO NOTHING;

-- Reset sequences to avoid ID conflicts with future inserts
SELECT setval('beworking.contact_profiles_id_seq', GREATEST((SELECT MAX(id) FROM beworking.contact_profiles), 1006), true);
SELECT setval('beworking.reservas_id_seq',         GREATEST((SELECT MAX(id) FROM beworking.reservas), 5018), true);
SELECT setval('beworking.bloqueos_id_seq',         GREATEST((SELECT MAX(id) FROM beworking.bloqueos), 9024), true);
SELECT setval('beworking.facturas_id_seq',         GREATEST((SELECT MAX(id) FROM beworking.facturas), 8019), true);
