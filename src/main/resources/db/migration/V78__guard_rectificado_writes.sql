-- V78: Make it impossible to set estado='Rectificado' on a factura
-- without going through InvoiceService.creditInvoice().
--
-- The legitimate refund path creates a Rectificativa row first (negative
-- total credit note) and only then flips the original to Rectificado.
-- Raw ad-hoc UPDATEs (psql, scripts, manual repairs) skipped step one
-- and produced the 4 orphan Rectificados cleaned up by V75 (€59.45),
-- plus the PT4963 phantom that V77 had to rectify.
--
-- The guard pattern:
--   - A trigger blocks any INSERT/UPDATE that sets estado='Rectificado'.
--   - Unless `beworking.allow_rectificado` = 'on' is set in the same
--     transaction (third arg true → tx-local, auto-clears at COMMIT).
--   - InvoiceService.creditInvoice() sets the flag before its UPDATE.
--
-- Idempotent: CREATE OR REPLACE + DROP-IF-EXISTS.

CREATE OR REPLACE FUNCTION beworking.guard_rectificado_write()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
  v_allow text;
BEGIN
  -- Only care when the new value is Rectificado AND it's actually a change.
  IF NEW.estado IS DISTINCT FROM 'Rectificado' THEN
    RETURN NEW;
  END IF;
  IF TG_OP = 'UPDATE' AND OLD.estado = 'Rectificado' THEN
    RETURN NEW;  -- no-op flip, nothing to guard
  END IF;

  -- current_setting(name, true) = NULL when unset, instead of raising.
  v_allow := current_setting('beworking.allow_rectificado', true);
  IF v_allow IS DISTINCT FROM 'on' THEN
    RAISE EXCEPTION
      'estado=Rectificado must be set via InvoiceService.creditInvoice (which sets beworking.allow_rectificado=on). Direct DML blocked on factura id=%.',
      COALESCE(NEW.id, -1)
      USING ERRCODE = 'check_violation';
  END IF;

  RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS guard_rectificado_write ON beworking.facturas;
CREATE TRIGGER guard_rectificado_write
  BEFORE INSERT OR UPDATE OF estado ON beworking.facturas
  FOR EACH ROW
  EXECUTE FUNCTION beworking.guard_rectificado_write();
