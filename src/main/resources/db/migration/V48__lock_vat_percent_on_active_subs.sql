-- VAT overhaul, stage 2 (2026-05-03):
-- Lock the legally-correct vat_percent onto every active subscription so the
-- monthly invoice generator stops re-resolving the rate every cycle. This is
-- the immediate fix for 89 customers seeing oscillating bills (€15 ↔ €18.15).
--
-- Decision logic per sub:
--   1. supplier_country  ← cuenta ('GT' → 'EE', else 'ES')
--   2. customer_country  ← prefix on billing_tax_id (if EU prefix present)
--                          else billing_country mapped to ISO-2
--                          else 'ES' (BeWorking default)
--   3. new_vat_percent:
--        IF type = 'eu_vat' AND vat_valid = TRUE AND customer ≠ supplier → 0
--        ELSE → standard customer-country rate (per EU rate map below)
--
-- After this migration, the per-cycle resolveVatPercent() path is dead weight.
-- Phase 4 (Task #107) will rip it out — invoice gen will read sub.vat_percent
-- directly. Idempotent: re-running on an already-locked DB updates 0 rows.

-- ── Stage 1: promote es_cif → eu_vat where the post-reseed VIES result is TRUE.
-- A Spanish CIF that VIES confirms is intra-community-VAT-registered should be
-- classified as eu_vat (Stripe's terminology) so the lock-in below applies the
-- reverse-charge rule when supplier and customer countries differ.
-- Idempotent: only flips rows currently typed es_cif.
UPDATE beworking.contact_profiles
   SET billing_tax_id_type = 'eu_vat'
 WHERE billing_tax_id_type = 'es_cif'
   AND vat_valid = TRUE;

-- ── Stage 2: lock vat_percent on every active subscription.
WITH target AS (
    SELECT
        s.id                                        AS sub_id,
        s.vat_percent                               AS current_vat,
        cp.billing_tax_id_type                      AS tid_type,
        cp.vat_valid                                AS vat_valid,
        CASE WHEN s.cuenta = 'GT' THEN 'EE' ELSE 'ES' END AS supplier_country,
        CASE
            WHEN UPPER(REGEXP_REPLACE(COALESCE(cp.billing_tax_id, ''), '\s+', '', 'g'))
                 ~ '^(AT|BE|BG|CY|CZ|DE|DK|EE|EL|ES|FI|FR|HR|HU|IE|IT|LT|LU|LV|MT|NL|PL|PT|RO|SE|SI|SK)'
                THEN SUBSTRING(UPPER(REGEXP_REPLACE(COALESCE(cp.billing_tax_id, ''), '\s+', '', 'g'))
                               FROM 1 FOR 2)
            WHEN cp.billing_country ILIKE 'spa%'
              OR cp.billing_country ILIKE 'españ%'
              OR cp.billing_country ILIKE 'espan%'           THEN 'ES'
            WHEN cp.billing_country ILIKE 'fran%'             THEN 'FR'
            WHEN cp.billing_country ILIKE 'germ%'
              OR cp.billing_country ILIKE 'aleman%'           THEN 'DE'
            WHEN cp.billing_country ILIKE 'ital%'             THEN 'IT'
            WHEN cp.billing_country ILIKE 'port%'             THEN 'PT'
            WHEN cp.billing_country ILIKE 'irel%'
              OR cp.billing_country ILIKE 'irlan%'            THEN 'IE'
            WHEN cp.billing_country ILIKE 'esto%'             THEN 'EE'
            WHEN cp.billing_country ILIKE 'neth%'
              OR cp.billing_country ILIKE 'pais%'             THEN 'NL'
            WHEN cp.billing_country ILIKE 'belg%'             THEN 'BE'
            WHEN cp.billing_country ILIKE 'austr%'            THEN 'AT'
            ELSE 'ES'  -- BeWorking default; safe fallback
        END                                          AS customer_country
      FROM beworking.subscriptions s
      JOIN beworking.contact_profiles cp ON cp.id = s.contact_id
     WHERE s.active = TRUE
),
computed AS (
    SELECT
        sub_id,
        current_vat,
        CASE
            -- B2B intra-EU reverse charge: VIES-confirmed eu_vat customer in a
            -- different country from the supplier → 0%.
            WHEN tid_type = 'eu_vat'
                 AND vat_valid = TRUE
                 AND customer_country <> supplier_country
                THEN 0
            -- Default: customer-country standard VAT rate.
            -- (Cross-border B2C digital services: OSS rules → customer-country rate.)
            WHEN customer_country = 'ES' THEN 21
            WHEN customer_country = 'EE' THEN 24
            WHEN customer_country = 'FR' THEN 20
            WHEN customer_country = 'DE' THEN 19
            WHEN customer_country = 'IT' THEN 22
            WHEN customer_country = 'PT' THEN 23
            WHEN customer_country = 'IE' THEN 23
            WHEN customer_country = 'NL' THEN 21
            WHEN customer_country = 'BE' THEN 21
            WHEN customer_country = 'AT' THEN 20
            WHEN customer_country = 'PL' THEN 23
            WHEN customer_country = 'CZ' THEN 21
            WHEN customer_country = 'DK' THEN 25
            WHEN customer_country = 'FI' THEN 25
            WHEN customer_country = 'SE' THEN 25
            WHEN customer_country = 'HU' THEN 27
            WHEN customer_country = 'EL' THEN 24
            WHEN customer_country = 'HR' THEN 25
            WHEN customer_country = 'RO' THEN 19
            WHEN customer_country = 'SK' THEN 23
            WHEN customer_country = 'SI' THEN 22
            WHEN customer_country = 'BG' THEN 20
            WHEN customer_country = 'LT' THEN 21
            WHEN customer_country = 'LV' THEN 21
            WHEN customer_country = 'LU' THEN 17
            WHEN customer_country = 'MT' THEN 18
            WHEN customer_country = 'CY' THEN 19
            ELSE 21  -- defensive fallback (shouldn't be hit since CASE above always returns)
        END AS new_vat
      FROM target
)
UPDATE beworking.subscriptions s
   SET vat_percent = c.new_vat,
       updated_at  = NOW()
  FROM computed c
 WHERE s.id = c.sub_id
   AND s.vat_percent IS DISTINCT FROM c.new_vat;
