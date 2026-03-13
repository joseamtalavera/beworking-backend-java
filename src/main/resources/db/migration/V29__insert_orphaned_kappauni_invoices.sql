-- Insert missing factura records for two orphaned Kappauni desk bookings (2026-03-13)
-- Bloqueo 65717 → MA1O1-1, PI pi_3TAQ0yIGBPwEtf1i1knwhQJG (€12.10)
-- Bloqueo 65718 → MA1O1-2, PI pi_3TAQB5IGBPwEtf1i1V77JJcp (€12.10)

DO $$
DECLARE
    v_cuenta_id    INT;
    v_centro_id    INT;
    v_cliente_id   BIGINT := 1773236658707;
    v_inv_num_1    TEXT;
    v_inv_id_1     INT;
    v_factura_id_1 BIGINT;
    v_desglose_id_1 BIGINT;
    v_inv_num_2    TEXT;
    v_inv_id_2     INT;
    v_factura_id_2 BIGINT;
    v_desglose_id_2 BIGINT;
    v_subtotal     NUMERIC(10,2) := 10.00;  -- €12.10 total = €10.00 + 21% VAT
    v_vat          NUMERIC(10,2) := 2.10;
    v_total        NUMERIC(10,2) := 12.10;
BEGIN
    -- Look up cuenta PT
    SELECT id INTO v_cuenta_id FROM beworking.cuentas WHERE codigo = 'PT';

    -- Look up centro from bloqueo
    SELECT id_centro INTO v_centro_id FROM beworking.bloqueos WHERE id = 65717;

    -- Generate invoice number 1
    UPDATE beworking.cuentas SET numero_secuencial = numero_secuencial + 1 WHERE id = v_cuenta_id
        RETURNING 'PT' || numero_secuencial INTO v_inv_num_1;
    v_inv_id_1 := CAST(regexp_replace(v_inv_num_1, '[^0-9]', '', 'g') AS INT);

    -- Insert factura 1
    v_factura_id_1 := nextval('beworking.facturas_id_seq');
    INSERT INTO beworking.facturas (
        id, idfactura, idcliente, idcentro, holdedcuenta, id_cuenta,
        descripcion, holdedinvoicenum,
        fechacreacionreal, estado,
        total, iva, totaliva, notas, creacionfecha,
        stripepaymentintentid1, stripepaymentintentstatus1
    ) VALUES (
        v_factura_id_1, v_inv_id_1, v_cliente_id, v_centro_id, 'PT', v_cuenta_id,
        'Reserva: MA1O1-1 (2026-03-13)', v_inv_num_1,
        '2026-03-13', 'Pagado',
        v_total, 21, v_vat, 'Desk booking MA1O1-1 (full day)', CURRENT_TIMESTAMP,
        'pi_3TAQ0yIGBPwEtf1i1knwhQJG', 'succeeded'
    );

    -- Insert desglose 1
    v_desglose_id_1 := nextval('beworking.facturasdesglose_id_seq');
    INSERT INTO beworking.facturasdesglose (
        id, idfacturadesglose, conceptodesglose, precioundesglose,
        cantidaddesglose, totaldesglose, desgloseconfirmado, idbloqueovinculado
    ) VALUES (
        v_desglose_id_1, v_inv_id_1,
        'Reserva MA1O1-1 00:00-23:59', v_subtotal,
        1, v_subtotal, 1, 65717
    );

    -- Generate invoice number 2
    UPDATE beworking.cuentas SET numero_secuencial = numero_secuencial + 1 WHERE id = v_cuenta_id
        RETURNING 'PT' || numero_secuencial INTO v_inv_num_2;
    v_inv_id_2 := CAST(regexp_replace(v_inv_num_2, '[^0-9]', '', 'g') AS INT);

    -- Insert factura 2
    v_factura_id_2 := nextval('beworking.facturas_id_seq');
    INSERT INTO beworking.facturas (
        id, idfactura, idcliente, idcentro, holdedcuenta, id_cuenta,
        descripcion, holdedinvoicenum,
        fechacreacionreal, estado,
        total, iva, totaliva, notas, creacionfecha,
        stripepaymentintentid1, stripepaymentintentstatus1
    ) VALUES (
        v_factura_id_2, v_inv_id_2, v_cliente_id, v_centro_id, 'PT', v_cuenta_id,
        'Reserva: MA1O1-2 (2026-03-13)', v_inv_num_2,
        '2026-03-13', 'Pagado',
        v_total, 21, v_vat, 'Desk booking MA1O1-2 (full day)', CURRENT_TIMESTAMP,
        'pi_3TAQB5IGBPwEtf1i1V77JJcp', 'succeeded'
    );

    -- Insert desglose 2
    v_desglose_id_2 := nextval('beworking.facturasdesglose_id_seq');
    INSERT INTO beworking.facturasdesglose (
        id, idfacturadesglose, conceptodesglose, precioundesglose,
        cantidaddesglose, totaldesglose, desgloseconfirmado, idbloqueovinculado
    ) VALUES (
        v_desglose_id_2, v_inv_id_2,
        'Reserva MA1O1-2 00:00-23:59', v_subtotal,
        1, v_subtotal, 1, 65718
    );

    RAISE NOTICE 'Created invoice % for bloqueo 65717 (PI: pi_3TAQ0yIGBPwEtf1i1knwhQJG)', v_inv_num_1;
    RAISE NOTICE 'Created invoice % for bloqueo 65718 (PI: pi_3TAQB5IGBPwEtf1i1V77JJcp)', v_inv_num_2;
END $$;
