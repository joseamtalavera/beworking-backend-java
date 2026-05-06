-- V55: Supplier portal credentials (2026-05-06)
--
-- For contacts at user_type='Proveedor' we store the credentials needed to
-- log into their supplier portal so admin can fetch invoices on their behalf.
-- Plaintext for now — DB is encrypted at rest by RDS and only admin reads.
-- Upgrade path to AES-GCM is documented in the ContactProfile entity.

ALTER TABLE beworking.contact_profiles
  ADD COLUMN IF NOT EXISTS supplier_portal_username VARCHAR(255) NULL;

ALTER TABLE beworking.contact_profiles
  ADD COLUMN IF NOT EXISTS supplier_portal_password VARCHAR(512) NULL;

ALTER TABLE beworking.contact_profiles
  ADD COLUMN IF NOT EXISTS supplier_portal_url VARCHAR(512) NULL;
