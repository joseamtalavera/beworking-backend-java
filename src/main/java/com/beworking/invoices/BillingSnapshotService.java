package com.beworking.invoices;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Freezes the billing identity onto a factura at creation time.
 *
 * An invoice is a legal document fixed at the moment it is issued. Without this,
 * the PDF read the customer's CURRENT contact_profiles row, so editing billing
 * details rewrote every past invoice. This copies name / tax id / tax-id type /
 * address from the contact's profile into the factura's billing_* columns once,
 * the first time the invoice is created.
 *
 * The {@code billing_snapshot_at IS NULL} guard makes this:
 *  - idempotent (re-running does nothing),
 *  - write-once (a frozen invoice is never silently rewritten — exactly the
 *    "billing changes are forward-only, never retroactive" rule).
 *
 * billing_vat_percent snapshots the factura's own {@code iva} so the frozen
 * identity carries the rate that applied at issue time.
 */
@Service
public class BillingSnapshotService {

    private static final Logger logger = LoggerFactory.getLogger(BillingSnapshotService.class);

    private final JdbcTemplate jdbcTemplate;

    public BillingSnapshotService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Snapshots the billing identity onto the given factura. No-op if the
     * factura is already frozen, has no customer, or the customer has no
     * profile row.
     *
     * @param facturaId beworking.facturas.id (internal PK)
     * @param contactId beworking.facturas.idcliente
     */
    public void snapshot(long facturaId, Long contactId) {
        if (contactId == null) {
            return;
        }
        try {
            int updated = jdbcTemplate.update(
                """
                UPDATE beworking.facturas f
                   SET billing_name        = COALESCE(NULLIF(cp.billing_name, ''), cp.name),
                       billing_tax_id      = cp.billing_tax_id,
                       billing_tax_id_type = cp.billing_tax_id_type,
                       billing_address     = cp.billing_address,
                       billing_postal_code = cp.billing_postal_code,
                       billing_city        = cp.billing_city,
                       billing_province    = cp.billing_province,
                       billing_country     = cp.billing_country,
                       billing_vat_percent = f.iva,
                       billing_snapshot_at = NOW()
                  FROM beworking.contact_profiles cp
                 WHERE f.id = ?
                   AND cp.id = ?
                   AND f.billing_snapshot_at IS NULL
                """,
                facturaId, contactId);
            if (updated == 0) {
                logger.debug("Billing snapshot skipped for factura {} (already frozen or no profile for contact {})",
                    facturaId, contactId);
            }
        } catch (Exception e) {
            // Never let a snapshot failure roll back invoice creation — the
            // legacy live fallback in InvoicePdfService still renders the PDF.
            logger.warn("Billing snapshot failed for factura {} / contact {}: {}",
                facturaId, contactId, e.getMessage());
        }
    }
}
