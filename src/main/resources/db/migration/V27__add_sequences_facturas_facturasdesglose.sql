-- Add sequences for facturas and facturasdesglose to replace unsafe MAX(id)+1 pattern.
-- This prevents duplicate-key failures under concurrent invoice creation.
DO $$
DECLARE
    max_facturas      BIGINT;
    max_facturasdesglose BIGINT;
BEGIN
    SELECT COALESCE(MAX(id), 0) + 1 INTO max_facturas FROM beworking.facturas;
    SELECT COALESCE(MAX(id), 0) + 1 INTO max_facturasdesglose FROM beworking.facturasdesglose;
    EXECUTE format('CREATE SEQUENCE IF NOT EXISTS beworking.facturas_id_seq START WITH %s INCREMENT BY 1', max_facturas);
    EXECUTE format('CREATE SEQUENCE IF NOT EXISTS beworking.facturasdesglose_id_seq START WITH %s INCREMENT BY 1', max_facturasdesglose);
END $$;

ALTER TABLE beworking.facturas         ALTER COLUMN id SET DEFAULT nextval('beworking.facturas_id_seq');
ALTER TABLE beworking.facturasdesglose ALTER COLUMN id SET DEFAULT nextval('beworking.facturasdesglose_id_seq');
