ALTER TABLE beworking.contact_profiles
  ADD COLUMN IF NOT EXISTS vat_valid BOOLEAN,
  ADD COLUMN IF NOT EXISTS vat_validated_at TIMESTAMP;
