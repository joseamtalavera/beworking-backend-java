-- V76: Correct mis-typed contacts that were dumping revenue into the
-- Overview "Extras" bucket. Each invoice was issued for a known product
-- (desk or OV) but the contact's tenant_type was either NULL or stale,
-- so the dashboard couldn't classify them.
--
-- Found during Phase 3 audit (2026-05-30): €4,404 of May MTD revenue
-- was hiding in Extras because one Free user (Volodymyr) booked 10 days
-- of desk MA1O1-5 and never got re-typed when he started paying.
--
-- Idempotent: each UPDATE is guarded so re-runs are a no-op.

-- 1) Volodymyr Lokotarov — booked MA1O1-5 desk (PT4963, €4,404.40)
UPDATE beworking.contact_profiles
SET tenant_type = 'Usuario Mesa'
WHERE id = (
        SELECT idcliente
        FROM beworking.facturas
        WHERE holdedinvoicenum = 'PT4963'
        LIMIT 1
      )
  AND COALESCE(tenant_type, '') IN ('', 'Usuario Free');

-- 2) Gavriil Zimuldinov — Oficina Virtual customer (GT5798, €18.15)
UPDATE beworking.contact_profiles
SET tenant_type = 'Usuario Virtual'
WHERE id = 91743
  AND COALESCE(tenant_type, '') = '';

-- 3) Francisco Jiménez Aguilar — Oficina Virtual customer (PT4928, €15)
UPDATE beworking.contact_profiles
SET tenant_type = 'Usuario Virtual'
WHERE id = 1779634731249
  AND COALESCE(tenant_type, '') = '';
