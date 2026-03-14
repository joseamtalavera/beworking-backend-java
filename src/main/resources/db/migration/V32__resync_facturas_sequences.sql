-- Resync facturas and facturasdesglose sequences to current MAX(id) + 1.
-- Some code paths use MAX(id)+1 instead of nextval(), causing the sequence to fall behind.
SELECT setval('beworking.facturas_id_seq', (SELECT COALESCE(MAX(id), 0) + 1 FROM beworking.facturas), false);
SELECT setval('beworking.facturasdesglose_id_seq', (SELECT COALESCE(MAX(id), 0) + 1 FROM beworking.facturasdesglose), false);
