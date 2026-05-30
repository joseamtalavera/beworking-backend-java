-- V79: Two unrelated cleanups, bundled to ship together.
--
-- (a) PT255029 is the V77 backfill invoice for Volodymyr Lokotarov's first
--     €108.90 sub charge. V77's holdedinvoicenum picker did MAX over
--     all PT-prefixed rows and got tripped by polluted high-numbered legacy
--     rows (~PT255028), producing the visually weird PT255029. The actual
--     next clean PT number was ~PT4964. Rename it and bump cuentas
--     numero_secuencial so the next app-issued invoice doesn't collide.
--
-- (b) contact_profiles.is_active is dead — zero readers in backend or
--     dashboard. It's written once on contact creation
--     (ContactProfileService.java) and never updated. ActivoAgingScheduler
--     maintains status, ignoring is_active. 495 of 2018 contacts have
--     drift between the two fields today; eliminating is_active removes
--     the entire bug class.

-- ─── (a) Renumber PT255029 ────────────────────────────────────────────
DO $$
DECLARE
  v_target_exists INT;
  v_max_clean     INT;
  v_new_num       INT;
  v_new_pt        TEXT;
BEGIN
  SELECT COUNT(*) INTO v_target_exists
  FROM beworking.facturas
  WHERE holdedinvoicenum = 'PT255029';

  IF v_target_exists = 0 THEN
    RAISE NOTICE 'V79: PT255029 not found, already fixed or never created — skipping';
    RETURN;
  END IF;

  -- True max of legitimate PT-suffix invoices (filter out historical pollution).
  SELECT COALESCE(MAX(CAST(SUBSTRING(holdedinvoicenum FROM 3) AS INTEGER)), 0)
    INTO v_max_clean
  FROM beworking.facturas
  WHERE holdedinvoicenum ~ '^PT[0-9]+$'
    AND CAST(SUBSTRING(holdedinvoicenum FROM 3) AS INTEGER) < 100000;

  v_new_num := v_max_clean + 1;
  v_new_pt  := 'PT' || v_new_num::text;

  UPDATE beworking.facturas
  SET holdedinvoicenum = v_new_pt
  WHERE holdedinvoicenum = 'PT255029';

  -- Bump cuentas.numero_secuencial so the next app-issued PT invoice picks
  -- v_new_num + 1 instead of colliding with our renamed row.
  UPDATE beworking.cuentas
  SET numero_secuencial = GREATEST(COALESCE(numero_secuencial, 0), v_new_num)
  WHERE codigo = 'PT';

  RAISE NOTICE 'V79: renamed PT255029 → %, bumped PT cuenta numero_secuencial to %', v_new_pt, v_new_num;
END $$;

-- ─── (b) Drop dead is_active column ────────────────────────────────────
ALTER TABLE beworking.contact_profiles DROP COLUMN IF EXISTS is_active;
