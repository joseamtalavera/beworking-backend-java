package com.beworking.tax;

import java.util.Map;
import java.util.Set;

/**
 * EU VAT rate table + EU country membership. Single source of truth for both,
 * replacing the duplicated Map and Set scattered across SubscriptionService,
 * MonthlyInvoiceScheduler, BookingService, InvoiceService, ContactProfileService.
 */
public final class EUVatRates {

    private EUVatRates() {}

    public static final int DEFAULT_FALLBACK_RATE = 21;

    public static final Set<String> EU_COUNTRIES = Set.of(
        "AT","BE","BG","CY","CZ","DE","DK","EE","EL","ES","FI","FR",
        "HR","HU","IE","IT","LT","LU","LV","MT","NL","PL","PT","RO",
        "SE","SI","SK","XI"
    );

    private static final Map<String, Integer> RATES = Map.ofEntries(
        Map.entry("AT", 20), Map.entry("BE", 21),
        Map.entry("BG", 20), Map.entry("CY", 19),
        Map.entry("CZ", 21), Map.entry("DE", 19),
        Map.entry("DK", 25), Map.entry("EE", 24),
        Map.entry("ES", 21), Map.entry("FI", 25),
        Map.entry("FR", 20), Map.entry("EL", 24),
        Map.entry("HR", 25), Map.entry("HU", 27),
        Map.entry("IE", 23), Map.entry("IT", 22),
        Map.entry("LT", 21), Map.entry("LU", 17),
        Map.entry("LV", 21), Map.entry("MT", 18),
        Map.entry("NL", 21), Map.entry("PL", 23),
        Map.entry("PT", 23), Map.entry("RO", 19),
        Map.entry("SE", 25), Map.entry("SI", 22),
        Map.entry("SK", 23)
    );

    public static int rateFor(String iso) {
        if (iso == null) return DEFAULT_FALLBACK_RATE;
        return RATES.getOrDefault(iso.toUpperCase(), DEFAULT_FALLBACK_RATE);
    }

    public static boolean isEuCountry(String iso) {
        return iso != null && EU_COUNTRIES.contains(iso.toUpperCase());
    }
}
