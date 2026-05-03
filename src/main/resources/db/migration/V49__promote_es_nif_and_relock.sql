-- VAT overhaul, stage 3 (2026-05-03):
-- Fix V48's overly restrictive promotion rule. V48 only promoted es_cif → eu_vat
-- when vat_valid=TRUE, missing Spanish autónomos (es_nif) who registered for
-- intra-community operations via Modelo 036/037. Per EU VAT Directive 2006/112,
-- Art. 196 — reverse charge applies to any taxable person VIES-registered in a
-- different member state, regardless of whether their tax ID format is CIF or NIF.
--
-- Concrete impact on staging dry-run: 22 GT-cuenta es_nif subs (incl. Daniil
-- Sereda Z2514413N) currently locked at 21% should drop to 0% reverse charge.

-- ── Stage 1: promote es_nif → eu_vat for VIES-valid autónomos ───────────
-- Idempotent: only flips rows currently typed es_nif AND vat_valid=TRUE.
UPDATE beworking.contact_profiles
   SET billing_tax_id_type = 'eu_vat'
 WHERE billing_tax_id_type = 'es_nif'
   AND vat_valid = TRUE;

-- ── Stage 2: re-run the V48 lock-in CASE so promoted contacts' subs flip ─
-- Same CASE as V48 (intentionally duplicated rather than refactored — Flyway
-- migrations should be self-contained). Idempotent: rows where vat_percent
-- already matches the new computed value are skipped via IS DISTINCT FROM.
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
            ELSE 'ES'
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
            WHEN tid_type = 'eu_vat'
                 AND vat_valid = TRUE
                 AND customer_country <> supplier_country
                THEN 0
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
            ELSE 21
        END AS new_vat
      FROM target
)
UPDATE beworking.subscriptions s
   SET vat_percent = c.new_vat,
       updated_at  = NOW()
  FROM computed c
 WHERE s.id = c.sub_id
   AND s.vat_percent IS DISTINCT FROM c.new_vat;
