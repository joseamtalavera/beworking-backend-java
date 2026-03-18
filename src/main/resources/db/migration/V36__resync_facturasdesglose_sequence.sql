-- Resync facturasdesglose sequence — duplicate key error on public booking invoice creation (2026-03-18).
SELECT setval('beworking.facturasdesglose_id_seq', (SELECT COALESCE(MAX(id), 0) + 1 FROM beworking.facturasdesglose), false);
SELECT setval('beworking.facturas_id_seq', (SELECT COALESCE(MAX(id), 0) + 1 FROM beworking.facturas), false);
