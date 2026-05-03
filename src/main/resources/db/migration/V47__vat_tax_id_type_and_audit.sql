-- VAT overhaul, stage 1 (2026-05-03):
--   1. Add billing_tax_id_type to contact_profiles so the user/admin can pick
--      the kind of tax ID explicitly (mirrors Stripe's UX). This eliminates
--      prefix-guessing as a downstream concern.
--   2. Add stickiness columns so VIES status no longer flips on transient
--      network errors (failure_streak + last_failure_at + status_changed_at).
--   3. Create vat_validations audit table — every VIES call goes here, with
--      consultation_number for AEAT inspections.
--   4. Add facturas.vat_validation_id FK so each reverse-charge invoice points
--      to the specific VIES check that justified it.
--   5. Backfill billing_tax_id_type from the existing billing_tax_id text via
--      regex classification. Uses historical vat_valid as a hint to upgrade
--      Spanish CIFs to eu_vat when they were already validating successfully.
--
-- Behaviour-neutral migration: nothing in code reads these columns yet. The
-- TaxResolver wiring lands in a follow-up commit. Rolling back this migration
-- only requires DROP COLUMN / DROP TABLE, no data loss.

-- ── 1. New columns on contact_profiles ────────────────────────────────
ALTER TABLE beworking.contact_profiles
    ADD COLUMN IF NOT EXISTS billing_tax_id_type    VARCHAR(32),
    ADD COLUMN IF NOT EXISTS vat_failure_streak     INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS vat_last_failure_at    TIMESTAMP,
    ADD COLUMN IF NOT EXISTS vat_status_changed_at  TIMESTAMP;

COMMENT ON COLUMN beworking.contact_profiles.billing_tax_id_type IS
    'Stripe-style tax ID type: es_cif | es_nif | eu_vat | no_vat. Drives VAT logic.';
COMMENT ON COLUMN beworking.contact_profiles.vat_failure_streak IS
    'Consecutive VIES failures or vat_valid=FALSE confirmations. Reset on TRUE.';
COMMENT ON COLUMN beworking.contact_profiles.vat_last_failure_at IS
    'Timestamp of most recent failure. Used by stickiness rule (require 2 fails ≥7d apart).';
COMMENT ON COLUMN beworking.contact_profiles.vat_status_changed_at IS
    'When vat_valid was last actually flipped. Audit trail.';

-- ── 2. vat_validations audit table ────────────────────────────────────
CREATE TABLE IF NOT EXISTS beworking.vat_validations (
    id                    BIGSERIAL PRIMARY KEY,
    contact_id            BIGINT NOT NULL REFERENCES beworking.contact_profiles(id),
    checked_at            TIMESTAMP NOT NULL,
    tax_id                VARCHAR(64) NOT NULL,
    country               VARCHAR(2)  NOT NULL,
    vies_result           VARCHAR(16) NOT NULL,  -- 'valid' | 'invalid' | 'unreachable'
    consultation_number   VARCHAR(64),
    request_payload       TEXT,
    response_payload      TEXT,
    CONSTRAINT vat_validations_result_check
        CHECK (vies_result IN ('valid', 'invalid', 'unreachable'))
);

CREATE INDEX IF NOT EXISTS idx_vat_validations_contact_checked
    ON beworking.vat_validations (contact_id, checked_at DESC);

COMMENT ON TABLE beworking.vat_validations IS
    'Append-only log of every VIES validation call. AEAT-defensible audit trail; '
    'consultation_number is the official VIES reference for that check.';

-- ── 3. Link facturas to the VIES check that justified its tax treatment ─
ALTER TABLE beworking.facturas
    ADD COLUMN IF NOT EXISTS vat_validation_id BIGINT;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.table_constraints
         WHERE constraint_name = 'facturas_vat_validation_id_fkey'
           AND table_schema    = 'beworking'
           AND table_name      = 'facturas'
    ) THEN
        ALTER TABLE beworking.facturas
            ADD CONSTRAINT facturas_vat_validation_id_fkey
            FOREIGN KEY (vat_validation_id)
            REFERENCES beworking.vat_validations(id);
    END IF;
END$$;

COMMENT ON COLUMN beworking.facturas.vat_validation_id IS
    'Points to the specific VIES check that justified this invoice''s VAT treatment. '
    'NULL for invoices issued before V47 or where no VIES check applies.';

-- ── 4. Backfill billing_tax_id_type from existing data ────────────────
-- Classification rules (regex over uppercased, whitespace-stripped tax_id):
--   '^(ES)?[ABCDEFGHJNPQRSUVW][0-9]{7}[0-9A-Z]$' → es_cif      (Spanish company CIF)
--   '^(ES)?[KLM0-9][0-9]{7}[A-Z]$'               → es_nif      (Spanish NIF: autónomo / personal)
--   '^(ES)?[XYZ][0-9]{7}[A-Z]$'                  → es_nif      (NIE: foreigner ID, treated as NIF)
--   '^(AT|BE|BG|CY|CZ|DE|DK|EE|EL|FI|FR|HR|HU|IE|IT|LT|LU|LV|MT|NL|PL|PT|RO|SE|SI|SK)\w+$' → eu_vat
--   tax_id IS NULL or empty                      → NULL        (admin to fill in)
--   else                                          → NULL        (admin manual review)
--
-- Stickiness hint: if a Spanish CIF already has vat_valid = TRUE, it's been
-- VIES-validated as intra-community → upgrade to eu_vat instead of es_cif.

UPDATE beworking.contact_profiles
   SET billing_tax_id_type = CASE
       -- Already-validated Spanish CIF → eu_vat (VIES-registered)
       WHEN vat_valid = TRUE
            AND UPPER(REGEXP_REPLACE(COALESCE(billing_tax_id, ''), '\s+', '', 'g'))
                ~ '^(ES)?[ABCDEFGHJNPQRSUVW][0-9]{7}[0-9A-Z]$'
            THEN 'eu_vat'
       -- Spanish company CIF (B/A/etc) — not VIES-confirmed
       WHEN UPPER(REGEXP_REPLACE(COALESCE(billing_tax_id, ''), '\s+', '', 'g'))
                ~ '^(ES)?[ABCDEFGHJNPQRSUVW][0-9]{7}[0-9A-Z]$'
            THEN 'es_cif'
       -- Spanish NIF (autónomo / personal)
       WHEN UPPER(REGEXP_REPLACE(COALESCE(billing_tax_id, ''), '\s+', '', 'g'))
                ~ '^(ES)?[KLM0-9][0-9]{7}[A-Z]$'
            THEN 'es_nif'
       -- Spanish NIE (foreigner) — treat as NIF
       WHEN UPPER(REGEXP_REPLACE(COALESCE(billing_tax_id, ''), '\s+', '', 'g'))
                ~ '^(ES)?[XYZ][0-9]{7}[A-Z]$'
            THEN 'es_nif'
       -- Other EU prefix → eu_vat
       WHEN UPPER(REGEXP_REPLACE(COALESCE(billing_tax_id, ''), '\s+', '', 'g'))
                ~ '^(AT|BE|BG|CY|CZ|DE|DK|EE|EL|FI|FR|HR|HU|IE|IT|LT|LU|LV|MT|NL|PL|PT|RO|SE|SI|SK)[A-Z0-9]+$'
            THEN 'eu_vat'
       ELSE NULL  -- admin to classify manually
   END
 WHERE billing_tax_id_type IS NULL
   AND billing_tax_id IS NOT NULL
   AND billing_tax_id <> '';

-- ── 5. Initialise vat_status_changed_at for existing VAT-valid contacts ──
-- So the audit trail has a sensible baseline (vat_validated_at as a proxy).
UPDATE beworking.contact_profiles
   SET vat_status_changed_at = vat_validated_at
 WHERE vat_status_changed_at IS NULL
   AND vat_validated_at IS NOT NULL;
