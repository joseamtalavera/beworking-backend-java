-- Phase 1 — billing-identity snapshot on facturas.
--
-- Until now an invoice stored only idcliente; the PDF read the customer's
-- CURRENT billing identity live from contact_profiles. Editing a customer's
-- billing details therefore retroactively rewrote every past invoice. An
-- invoice is a legal document fixed at issue time — it must not change after.
--
-- These columns hold a frozen copy of the billing identity captured when the
-- invoice is created (see BillingSnapshotService). billing_snapshot_at is the
-- "frozen" marker: NULL = legacy/unsnapshotted (read path falls back to live
-- contact_profiles); NOT NULL = use the snapshot, never overwrite again.
--
-- Idempotent: ADD COLUMN IF NOT EXISTS + guarded backfill.

ALTER TABLE beworking.facturas
    ADD COLUMN IF NOT EXISTS billing_name         VARCHAR(255),
    ADD COLUMN IF NOT EXISTS billing_tax_id       VARCHAR(64),
    ADD COLUMN IF NOT EXISTS billing_tax_id_type  VARCHAR(32),
    ADD COLUMN IF NOT EXISTS billing_address      VARCHAR(255),
    ADD COLUMN IF NOT EXISTS billing_postal_code  VARCHAR(32),
    ADD COLUMN IF NOT EXISTS billing_city         VARCHAR(128),
    ADD COLUMN IF NOT EXISTS billing_province     VARCHAR(128),
    ADD COLUMN IF NOT EXISTS billing_country      VARCHAR(128),
    ADD COLUMN IF NOT EXISTS billing_vat_percent  INTEGER,
    ADD COLUMN IF NOT EXISTS billing_snapshot_at  TIMESTAMP;

-- Backfill legacy rows from the customer's current profile and freeze them.
-- Their details ≈ historical for ~every customer; the known exception
-- (Mariano / contact 91687) is corrected by the Phase 2 migration, which runs
-- after this one.
UPDATE beworking.facturas f
   SET billing_name        = COALESCE(NULLIF(cp.billing_name, ''), cp.name),
       billing_tax_id      = cp.billing_tax_id,
       billing_tax_id_type = cp.billing_tax_id_type,
       billing_address     = cp.billing_address,
       billing_postal_code = cp.billing_postal_code,
       billing_city        = cp.billing_city,
       billing_province    = cp.billing_province,
       billing_country     = cp.billing_country,
       billing_vat_percent = f.iva,
       billing_snapshot_at = NOW()
  FROM beworking.contact_profiles cp
 WHERE cp.id = f.idcliente
   AND f.billing_snapshot_at IS NULL;
