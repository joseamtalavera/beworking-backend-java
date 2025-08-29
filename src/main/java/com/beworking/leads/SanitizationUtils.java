package com.beworking.leads;

public class SanitizationUtils {
    // remove HTML tags and trim whitespace
    public static String sanitizeText(String input) {
        if (input == null) return null;
        return input.replaceAll("<[^>]*>", "").trim();
    }

    // Allows only digits, spaces, +, -, (, ) in phone numbers
    public static String sanitizePhone(String phone) {
        if (phone == null) return null;
        return phone.replaceAll("[^\\d\\s+\\-()]", "").trim();
    }
}