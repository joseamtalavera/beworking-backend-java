-- Create sequences for reservas and bloqueos tables to replace unsafe MAX(id)+1 pattern
DO $$
DECLARE
    max_reservas BIGINT;
    max_bloqueos BIGINT;
BEGIN
    SELECT COALESCE(MAX(id), 0) + 1 INTO max_reservas FROM beworking.reservas;
    SELECT COALESCE(MAX(id), 0) + 1 INTO max_bloqueos FROM beworking.bloqueos;
    EXECUTE format('CREATE SEQUENCE IF NOT EXISTS beworking.reservas_id_seq START WITH %s INCREMENT BY 1', max_reservas);
    EXECUTE format('CREATE SEQUENCE IF NOT EXISTS beworking.bloqueos_id_seq START WITH %s INCREMENT BY 1', max_bloqueos);
END $$;

ALTER TABLE beworking.reservas ALTER COLUMN id SET DEFAULT nextval('beworking.reservas_id_seq');
ALTER TABLE beworking.bloqueos ALTER COLUMN id SET DEFAULT nextval('beworking.bloqueos_id_seq');
