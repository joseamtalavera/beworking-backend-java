package com.beworking.tax;

import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Single entry point for VAT-percentage resolution. Replaces the four
 * duplicated {@code resolveContactVatPercent}/{@code resolveVatPercent}
 * implementations that used to live in SubscriptionService,
 * MonthlyInvoiceScheduler, BookingService, and InvoiceService.
 *
 * <p>Two modes:
 * <ul>
 *   <li>{@link #resolveForContact}: read the locked rate from the contact's
 *       active subscription if present, else compute fresh from billing data.
 *       Used by every invoice-creation path. NO VIES side effects in the lock-in
 *       case — this is the path taken by ~99% of monthly invoice generation.</li>
 *   <li>{@link #computeFreshForContact}: always compute from billing data
 *       (current vat_valid + tax ID + country). No persistence. Used by the
 *       admin "Re-validate VAT" trigger after a deliberate VIES refresh.</li>
 * </ul>
 *
 * <p>This component does NOT call VIES itself; that's handled upstream by
 * {@link com.beworking.contacts.ContactProfileService#ensureVatValidated} for
 * the legacy fresh path, and by {@link ViesGateway} for explicit revalidation
 * flows.
 */
@Component
public class TaxResolver {

    private static final Logger logger = LoggerFactory.getLogger(TaxResolver.class);

    private final JdbcTemplate jdbcTemplate;

    public TaxResolver(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Returns the VAT % to apply to invoices for this contact + cuenta combo.
     * Prefers the active subscription's locked rate; falls back to a fresh
     * compute when no sub or no lock exists.
     */
    public int resolveForContact(Long contactId, String cuenta) {
        Optional<Integer> locked = readLockedRate(contactId);
        if (locked.isPresent()) return locked.get();
        int fresh = computeFreshForContact(contactId, cuenta);
        logger.debug("TaxResolver: contact {} cuenta {} → {}% (legacy fresh path)",
            contactId, cuenta, fresh);
        return fresh;
    }

    /**
     * Always computes from billing data, ignoring any locked sub rate. Use
     * after a deliberate VIES refresh (admin re-validate flow), or for
     * subscriptions that haven't been locked yet.
     */
    public int computeFreshForContact(Long contactId, String cuenta) {
        String supplierCountry = "GT".equalsIgnoreCase(cuenta) ? "EE" : "ES";

        String taxId = null;
        String billingCountry = null;
        Boolean vatValid = null;
        try {
            Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT billing_tax_id, billing_country, vat_valid "
                + "FROM beworking.contact_profiles WHERE id = ?",
                contactId);
            taxId = (String) row.get("billing_tax_id");
            billingCountry = (String) row.get("billing_country");
            vatValid = (Boolean) row.get("vat_valid");
        } catch (EmptyResultDataAccessException ignored) {}

        String customerCountry = deriveCustomerCountry(taxId, billingCountry);
        if (customerCountry == null) {
            return EUVatRates.rateFor(supplierCountry);
        }

        boolean reverseCharge = Boolean.TRUE.equals(vatValid)
            && !supplierCountry.equals(customerCountry);
        if (reverseCharge) return 0;
        return EUVatRates.rateFor(customerCountry);
    }

    /**
     * Resolves the customer country for VAT purposes:
     *   1. EU country prefix in the tax ID (e.g., "ESB09665258" → ES).
     *   2. billing_country mapped via countryNameToIso.
     *   3. ES default (BeWorking is Spain-based).
     * Returns null if neither yields an EU country.
     */
    public static String deriveCustomerCountry(String taxId, String billingCountry) {
        if (taxId != null && !taxId.isBlank()) {
            String normalized = taxId.trim().replaceAll("\\s+", "").toUpperCase();
            if (normalized.length() >= 2 && EUVatRates.isEuCountry(normalized.substring(0, 2))) {
                return normalized.substring(0, 2);
            }
        }
        String iso = countryNameToIso(billingCountry);
        if (iso == null) iso = "ES";
        return EUVatRates.isEuCountry(iso) ? iso : null;
    }

    public static String countryNameToIso(String countryName) {
        if (countryName == null || countryName.isBlank()) return null;
        String s = countryName.trim();
        if (s.length() == 2) return s.toUpperCase();
        return switch (s.toLowerCase()) {
            case "spain", "españa", "espana" -> "ES";
            case "france", "francia" -> "FR";
            case "germany", "alemania" -> "DE";
            case "italy", "italia" -> "IT";
            case "portugal" -> "PT";
            case "ireland", "irlanda" -> "IE";
            case "estonia" -> "EE";
            case "netherlands", "países bajos", "paises bajos" -> "NL";
            case "belgium", "bélgica", "belgica" -> "BE";
            case "austria" -> "AT";
            case "poland", "polonia" -> "PL";
            case "czech republic", "czechia", "república checa", "republica checa" -> "CZ";
            case "denmark", "dinamarca" -> "DK";
            case "finland", "finlandia" -> "FI";
            case "sweden", "suecia" -> "SE";
            case "greece", "grecia" -> "EL";
            case "hungary", "hungría", "hungria" -> "HU";
            case "romania", "rumanía", "rumania" -> "RO";
            case "bulgaria" -> "BG";
            case "croatia", "croacia" -> "HR";
            case "slovakia", "eslovaquia" -> "SK";
            case "slovenia", "eslovenia" -> "SI";
            case "lithuania", "lituania" -> "LT";
            case "latvia", "letonia" -> "LV";
            case "luxembourg", "luxemburgo" -> "LU";
            case "malta" -> "MT";
            case "cyprus", "chipre" -> "CY";
            default -> null;
        };
    }

    private Optional<Integer> readLockedRate(Long contactId) {
        try {
            Integer rate = jdbcTemplate.queryForObject(
                "SELECT vat_percent FROM beworking.subscriptions "
                + "WHERE contact_id = ? AND active = TRUE AND vat_percent IS NOT NULL "
                + "ORDER BY id LIMIT 1",
                Integer.class, contactId);
            return Optional.ofNullable(rate);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }
}
