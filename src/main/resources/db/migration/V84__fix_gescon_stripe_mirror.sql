-- V84: Mirror GESCON TRAMITEC SL invoices to Stripe (amounts + dates).
--
-- The 6 visible Stripe rows for customer cus_TP4pXpSZzzsl3J were reconciled
-- against beworking.facturas (2026-06-01). Three drifts were found:
--   * GT5466 was stored at 15.00 / 0% VAT, but Stripe actually charged
--     18.15 / 21% on 30 Mar (the VAT-exempt change only reached Stripe in
--     April). Fix the amount + VAT.
--   * GT5466 / GT5831 / GT5920 were stamped just after midnight UTC, so the
--     dashboard rendered them one calendar day AFTER Stripe's charge day
--     (GT5831 even fell into the wrong month). Move each back one day —
--     time-of-day preserved via the explicit timestamps below.
--   * F255685 (Dec) / F266053 (Jan) legacy Holded rows were dated the 1st of
--     the month; Stripe charged on the 30th. Move to the 30th.
--
-- Scoped by (holdedinvoicenum, GESCON's idcliente) so it only ever touches
-- GESCON's own rows — on staging / fresh DBs where this contact does not
-- exist the subquery is NULL and nothing matches. Idempotent: explicit target
-- values, safe to re-run. No estado change, so the V78 Rectificado guard is
-- not involved.

DO $$
DECLARE
    gescon_id BIGINT;
BEGIN
    SELECT id INTO gescon_id
    FROM beworking.contact_profiles
    WHERE name ILIKE '%GESCON TRAMITEC%'
    ORDER BY id
    LIMIT 1;

    IF gescon_id IS NULL THEN
        RAISE NOTICE 'V84: GESCON TRAMITEC not found in this database — skipping.';
        RETURN;
    END IF;

    -- GT5466: amount 15.00/0% -> 18.15/21%, date 31 Mar -> 30 Mar (time kept).
    UPDATE beworking.facturas
    SET total = 18.15, totaliva = 3.15, iva = 21,
        creacionfecha = TIMESTAMP '2026-03-30 00:03:08.80187'
    WHERE idcliente = gescon_id AND holdedinvoicenum = 'GT5466';

    -- GT5831: 01 May -> 30 Apr (time kept).
    UPDATE beworking.facturas
    SET creacionfecha = TIMESTAMP '2026-04-30 00:06:53.370698'
    WHERE idcliente = gescon_id AND holdedinvoicenum = 'GT5831';

    -- GT5920: 31 May -> 30 May (time kept).
    UPDATE beworking.facturas
    SET creacionfecha = TIMESTAMP '2026-05-30 00:03:25.244122'
    WHERE idcliente = gescon_id AND holdedinvoicenum = 'GT5920';

    -- F255685 (Dec): 01 Dec -> 30 Dec.
    UPDATE beworking.facturas
    SET creacionfecha = TIMESTAMP '2025-12-30 00:00:00'
    WHERE idcliente = gescon_id AND holdedinvoicenum = 'F255685';

    -- F266053 (Jan): 01 Jan -> 30 Jan.
    UPDATE beworking.facturas
    SET creacionfecha = TIMESTAMP '2026-01-30 00:00:00'
    WHERE idcliente = gescon_id AND holdedinvoicenum = 'F266053';
END $$;
