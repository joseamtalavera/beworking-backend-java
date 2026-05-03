package com.beworking.tax;

import org.springframework.stereotype.Component;

/**
 * Canonicalizes tax IDs at write-time so downstream code never has to guess
 * country from billing_country fallback paths. Brute-forcing prefix candidates
 * against VIES is rejected (rate-limited, slow); instead we require the caller
 * to provide either a tax ID with an explicit prefix already, or a known
 * billing country to derive one.
 *
 * <p>Spec from the user (2026-05-03):
 * <pre>
 *   1. Strip whitespace, uppercase.
 *   2. If the first 2 chars are an EU country prefix → keep as-is.
 *   3. Else, prepend the ISO derived from billing_country.
 *   4. Else, default to ES (BeWorking is Spain-based).
 * </pre>
 */
@Component
public class TaxIdNormalizer {

    /** Returns the canonical form, or null if rawTaxId is null/blank. */
    public String canonicalize(String rawTaxId, String billingCountryIso) {
        if (rawTaxId == null) return null;
        String stripped = rawTaxId.trim().replaceAll("\\s+", "").toUpperCase();
        if (stripped.isEmpty()) return null;

        if (stripped.length() >= 2 && EUVatRates.isEuCountry(stripped.substring(0, 2))) {
            return stripped;
        }

        String prefix = (billingCountryIso != null && EUVatRates.isEuCountry(billingCountryIso))
            ? billingCountryIso.toUpperCase()
            : "ES";  // BeWorking default
        return prefix + stripped;
    }
}
