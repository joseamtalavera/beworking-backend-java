-- Create cuentas table for company/account management
CREATE TABLE IF NOT EXISTS beworking.cuentas (
    id SERIAL PRIMARY KEY,
    codigo VARCHAR(10) NOT NULL UNIQUE,
    nombre VARCHAR(100) NOT NULL,
    descripcion TEXT,
    activo BOOLEAN DEFAULT TRUE,
    prefijo_factura VARCHAR(5) DEFAULT 'F',
    numero_secuencial INTEGER DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Insert existing cuentas
INSERT INTO beworking.cuentas (codigo, nombre, descripcion, prefijo_factura, numero_secuencial) VALUES
('GT', 'Globaltechno', 'Globaltechno company invoices', 'GT', 4750),
('OF', 'Offices', 'Offices company invoices', 'OF', 1100),
('PT', 'Partners', 'Partners company invoices', 'PT', 4010)
ON CONFLICT (codigo) DO NOTHING;

-- Add id_cuenta column to facturas
ALTER TABLE beworking.facturas
ADD COLUMN IF NOT EXISTS id_cuenta INTEGER REFERENCES beworking.cuentas(id);

CREATE INDEX IF NOT EXISTS idx_facturas_id_cuenta ON beworking.facturas(id_cuenta);
CREATE INDEX IF NOT EXISTS idx_cuentas_codigo ON beworking.cuentas(codigo);

-- Backfill id_cuenta for existing invoices
UPDATE beworking.facturas
SET id_cuenta = (SELECT id FROM beworking.cuentas WHERE codigo = 'GT')
WHERE holdedcuenta = 'Globaltechno' AND id_cuenta IS NULL;

UPDATE beworking.facturas
SET id_cuenta = (SELECT id FROM beworking.cuentas WHERE codigo = 'OF')
WHERE holdedcuenta = 'Offices' AND id_cuenta IS NULL;

UPDATE beworking.facturas
SET id_cuenta = (SELECT id FROM beworking.cuentas WHERE codigo = 'PT')
WHERE holdedcuenta = 'Partners' AND id_cuenta IS NULL;
