-- Orphaned Kappauni invoices for bloqueos 65717/65718 were inserted manually on 2026-03-13.
-- Sync sequences to current max to prevent stale-sequence errors.
SELECT setval('beworking.facturas_id_seq', GREATEST(nextval('beworking.facturas_id_seq'), (SELECT MAX(id) + 1 FROM beworking.facturas)));
SELECT setval('beworking.facturasdesglose_id_seq', GREATEST(nextval('beworking.facturasdesglose_id_seq'), (SELECT MAX(id) + 1 FROM beworking.facturasdesglose)));
