package com.beworking.tax;

import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Classifies a raw tax ID string into a {@link TaxIdType} via regex. Used both
 * at write-time (when the user types into the form) and at backfill-time
 * (V47 migration replicates these same rules in SQL).
 *
 * <p>Whitespace is stripped and the result is uppercased before matching.
 */
@Component
public class TaxIdClassifier {

    private static final Pattern ES_CIF = Pattern.compile("^(ES)?[ABCDEFGHJNPQRSUVW][0-9]{7}[0-9A-Z]$");
    private static final Pattern ES_NIF_NUMERIC = Pattern.compile("^(ES)?[KLM0-9][0-9]{7}[A-Z]$");
    private static final Pattern ES_NIE = Pattern.compile("^(ES)?[XYZ][0-9]{7}[A-Z]$");
    private static final Pattern EU_PREFIX = Pattern.compile(
        "^(AT|BE|BG|CY|CZ|DE|DK|EE|EL|FI|FR|HR|HU|IE|IT|LT|LU|LV|MT|NL|PL|PT|RO|SE|SI|SK)[A-Z0-9]+$"
    );

    public TaxIdType classify(String rawTaxId) {
        if (rawTaxId == null || rawTaxId.isBlank()) return TaxIdType.NO_VAT;
        String normalized = rawTaxId.trim().replaceAll("\\s+", "").toUpperCase();
        if (normalized.isEmpty()) return TaxIdType.NO_VAT;
        if (ES_CIF.matcher(normalized).matches()) return TaxIdType.ES_CIF;
        if (ES_NIF_NUMERIC.matcher(normalized).matches()) return TaxIdType.ES_NIF;
        if (ES_NIE.matcher(normalized).matches()) return TaxIdType.ES_NIF;
        if (EU_PREFIX.matcher(normalized).matches()) return TaxIdType.EU_VAT;
        return null; // unclassified — admin must type explicitly
    }
}
