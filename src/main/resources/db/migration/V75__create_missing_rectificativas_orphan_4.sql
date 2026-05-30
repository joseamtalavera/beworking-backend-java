-- V75: For each of the 4 orphan Rectificados (positive estado='Rectificado'
-- with no matching credit note), create the missing Rectificativa by
-- mirroring InvoiceService.creditInvoice() — negative-total credit row +
-- negated desglose lines. Idempotent: skips originals that already have a
-- credit (so safe to re-run on any environment).
--
-- Targets: GT5365, GT5364, F266010, PT4379 (total exposure €59.45).
-- See issue #232 for the orphan diagnostic and decision trail.

DO $$
DECLARE
  v_orig RECORD;
  v_line RECORD;
  v_new_id BIGINT;
  v_new_legacy INT;
  v_new_num TEXT;
  v_desc TEXT;
  v_exists INT;
BEGIN
  FOR v_orig IN
    SELECT id, idfactura, idcliente, idcentro, total, iva, totaliva,
           holdedcuenta, id_cuenta, holdedinvoicenum, category
    FROM beworking.facturas
    WHERE holdedinvoicenum IN ('GT5365','GT5364','F266010','PT4379')
      AND estado = 'Rectificado'
  LOOP
    SELECT COUNT(*) INTO v_exists
    FROM beworking.facturas
    WHERE total < 0
      AND (
            holdedinvoicenum LIKE 'RECT-' || v_orig.holdedinvoicenum || '%'
         OR descripcion ILIKE '%' || v_orig.holdedinvoicenum || '%'
         OR descripcion ILIKE '%#' || v_orig.idfactura::text || '%'
      );
    IF v_exists > 0 THEN CONTINUE; END IF;

    v_new_id     := nextval('beworking.facturas_id_seq');
    v_new_legacy := (SELECT COALESCE(MAX(idfactura), 0) + 1
                       FROM beworking.facturas
                      WHERE holdedinvoicenum LIKE 'PT%' AND idfactura < 100000);
    v_new_num    := 'RECT-' || v_orig.holdedinvoicenum;
    v_desc       := 'Rectificación de Factura #' || v_orig.holdedinvoicenum;

    INSERT INTO beworking.facturas
      (id, idfactura, idcliente, idcentro, descripcion, total, iva, totaliva,
       estado, creacionfecha, holdedcuenta, id_cuenta, holdedinvoicenum, category)
    VALUES
      (v_new_id, v_new_legacy, v_orig.idcliente, v_orig.idcentro, v_desc,
       -v_orig.total, v_orig.iva, -v_orig.totaliva,
       'Rectificativa', CURRENT_TIMESTAMP,
       v_orig.holdedcuenta, v_orig.id_cuenta, v_new_num, v_orig.category);

    FOR v_line IN
      SELECT conceptodesglose, precioundesglose, cantidaddesglose, totaldesglose
      FROM beworking.facturasdesglose
      WHERE idfacturadesglose = v_orig.idfactura
    LOOP
      INSERT INTO beworking.facturasdesglose
        (id, idfacturadesglose, conceptodesglose, precioundesglose,
         cantidaddesglose, totaldesglose, desgloseconfirmado, factura_id)
      VALUES
        (nextval('beworking.facturasdesglose_id_seq'), v_new_legacy,
         'Rectificación: ' || COALESCE(v_line.conceptodesglose, '—'),
         -COALESCE(v_line.precioundesglose, 0),
         COALESCE(v_line.cantidaddesglose, 1),
         -COALESCE(v_line.totaldesglose, 0),
         1, v_new_id);
    END LOOP;
  END LOOP;
END $$;
