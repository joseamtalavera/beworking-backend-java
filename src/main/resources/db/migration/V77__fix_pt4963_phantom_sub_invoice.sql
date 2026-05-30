-- V77: Clean up PT4963, the €4,404.40 phantom invoice generated 2026-05-27
-- by the (now-fixed) auto-invoice path when a sub-backed booking spans
-- multiple days. Volodymyr Lokotarov's actual Stripe charge for the
-- 2026-06 month of his coworking subscription was €108.90
-- (PI pi_3TbloAIGBPwEtf1i0cnaSe31).
--
-- Three steps, all idempotent:
--   (a) Create Rectificativa for PT4963 (-€4,404.40, negated desglose).
--   (b) Flip PT4963.estado = 'Rectificado'.
--   (c) Create the real €108.90 sub invoice (Pagado, linked to the PI).
--
-- Bloqueos on MA1O1-5 are left alone — they represent legitimate booked
-- days the customer paid for; the booking-side fix shipped 2026-05-28
-- means no further phantom invoices will be generated.

DO $$
DECLARE
  v_orig            RECORD;
  v_pi              TEXT := 'pi_3TbloAIGBPwEtf1i0cnaSe31';
  v_charge_ts       TIMESTAMP := '2026-05-27 20:04:00';
  v_credit_id       BIGINT;
  v_credit_legacy   INT;
  v_real_id         BIGINT;
  v_real_legacy     INT;
  v_real_invoicenum TEXT;
  v_credit_exists   INT;
  v_real_exists     INT;
BEGIN
  SELECT id, idfactura, idcliente, idcentro, total, iva, totaliva,
         holdedcuenta, id_cuenta, holdedinvoicenum, category, estado
  INTO v_orig
  FROM beworking.facturas
  WHERE holdedinvoicenum = 'PT4963'
  LIMIT 1;

  IF v_orig.id IS NULL THEN
    RAISE NOTICE 'V77: PT4963 not found, skipping';
    RETURN;
  END IF;

  -- (a) Rectificativa for the phantom invoice
  SELECT COUNT(*) INTO v_credit_exists
  FROM beworking.facturas
  WHERE total < 0
    AND (holdedinvoicenum LIKE 'RECT-PT4963%'
      OR descripcion ILIKE '%PT4963%'
      OR descripcion ILIKE '%#' || v_orig.idfactura::text || '%');

  IF v_credit_exists = 0 THEN
    v_credit_id     := nextval('beworking.facturas_id_seq');
    v_credit_legacy := (SELECT COALESCE(MAX(idfactura), 0) + 1
                         FROM beworking.facturas
                        WHERE holdedinvoicenum LIKE 'PT%' AND idfactura < 100000);

    INSERT INTO beworking.facturas
      (id, idfactura, idcliente, idcentro, descripcion, total, iva, totaliva,
       estado, creacionfecha, holdedcuenta, id_cuenta, holdedinvoicenum, category)
    VALUES
      (v_credit_id, v_credit_legacy, v_orig.idcliente, v_orig.idcentro,
       'Rectificación de Factura #PT4963',
       -v_orig.total, v_orig.iva, -COALESCE(v_orig.totaliva, 0),
       'Rectificativa', CURRENT_TIMESTAMP,
       v_orig.holdedcuenta, v_orig.id_cuenta, 'RECT-PT4963', v_orig.category);

    INSERT INTO beworking.facturasdesglose
      (id, idfacturadesglose, conceptodesglose, precioundesglose,
       cantidaddesglose, totaldesglose, desgloseconfirmado, factura_id)
    SELECT
      nextval('beworking.facturasdesglose_id_seq'),
      v_credit_legacy,
      'Rectificación: ' || COALESCE(conceptodesglose, '—'),
      -COALESCE(precioundesglose, 0),
      COALESCE(cantidaddesglose, 1),
      -COALESCE(totaldesglose, 0),
      1, v_credit_id
    FROM beworking.facturasdesglose
    WHERE idfacturadesglose = v_orig.idfactura;
  END IF;

  -- (b) Mark original as Rectificado
  UPDATE beworking.facturas
  SET estado = 'Rectificado'
  WHERE id = v_orig.id AND estado <> 'Rectificado';

  -- (c) Real €108.90 sub invoice — idempotent on (PI, amount)
  SELECT COUNT(*) INTO v_real_exists
  FROM beworking.facturas
  WHERE stripepaymentintentid1 = v_pi AND total = 108.90;

  IF v_real_exists = 0 THEN
    v_real_id     := nextval('beworking.facturas_id_seq');
    v_real_legacy := (SELECT COALESCE(MAX(idfactura), 0) + 1
                        FROM beworking.facturas
                       WHERE holdedinvoicenum LIKE 'PT%' AND idfactura < 100000);
    v_real_invoicenum := 'PT' || (
      SELECT COALESCE(MAX(CAST(SUBSTRING(holdedinvoicenum FROM 3) AS INTEGER)), 0) + 1
      FROM beworking.facturas
      WHERE holdedinvoicenum ~ '^PT[0-9]+$'
    )::text;

    INSERT INTO beworking.facturas
      (id, idfactura, idcliente, idcentro, descripcion, total, iva, totaliva,
       estado, creacionfecha, fechacreacionreal,
       holdedcuenta, id_cuenta, holdedinvoicenum, category,
       stripepaymentintentid1, stripepaymentintentstatus1, notas)
    VALUES
      (v_real_id, v_real_legacy, v_orig.idcliente, v_orig.idcentro,
       'Suscripción Mesa Coworking MA1O1-5 — 2026-06',
       108.90, 21, 18.90,
       'Pagado', v_charge_ts, v_charge_ts,
       v_orig.holdedcuenta, v_orig.id_cuenta, v_real_invoicenum, 'coworking',
       v_pi, 'succeeded',
       'V77 backfill — real Stripe charge; replaces phantom PT4963');

    INSERT INTO beworking.facturasdesglose
      (id, idfacturadesglose, conceptodesglose, precioundesglose,
       cantidaddesglose, totaldesglose, desgloseconfirmado, factura_id)
    VALUES
      (nextval('beworking.facturasdesglose_id_seq'), v_real_legacy,
       'Suscripción Mesa Coworking MA1O1-5 — 2026-06',
       90.00, 1, 90.00, 1, v_real_id);
  END IF;
END $$;
