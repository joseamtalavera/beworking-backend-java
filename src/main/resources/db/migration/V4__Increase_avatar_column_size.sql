-- Increase avatar column size to support larger base64 images
ALTER TABLE beworking.users ALTER COLUMN avatar TYPE TEXT;
ALTER TABLE beworking.contact_profiles ALTER COLUMN avatar TYPE TEXT;
