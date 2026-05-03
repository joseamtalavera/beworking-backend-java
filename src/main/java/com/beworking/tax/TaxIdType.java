package com.beworking.tax;

/**
 * Stripe-style tax ID classification. Drives the reverse-charge decision tree:
 * only EU_VAT can earn 0% reverse charge (and only when VIES says valid AND
 * customer ≠ supplier country).
 *
 * <p>Persisted as a string in {@code contact_profiles.billing_tax_id_type}.
 */
public enum TaxIdType {
    /** Spanish company tax ID (CIF, B/A/etc prefix). Not VIES-confirmed. */
    ES_CIF("es_cif"),

    /** Spanish individual tax ID (NIF/DNI/NIE). Never reverse-charge eligible. */
    ES_NIF("es_nif"),

    /** EU intra-community VAT number, validated against VIES. Reverse-charge eligible. */
    EU_VAT("eu_vat"),

    /** No tax ID provided. Treated as B2C consumer. */
    NO_VAT("no_vat");

    private final String code;

    TaxIdType(String code) { this.code = code; }

    public String code() { return code; }

    public static TaxIdType fromCode(String code) {
        if (code == null) return null;
        for (TaxIdType t : values()) if (t.code.equals(code)) return t;
        return null;
    }

    /** Whether this type CAN earn reverse charge (subject to VIES + cross-border check). */
    public boolean isReverseChargeEligible() {
        return this == EU_VAT;
    }
}
