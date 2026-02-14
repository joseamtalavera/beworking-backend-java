package com.beworking.mailroom;

/**
 * Distinguishes between scanned mail documents and package arrivals.
 */
public enum MailroomDocumentType {
    MAIL,
    PACKAGE;

    public String toApiValue() {
        return name().toLowerCase();
    }

    public static MailroomDocumentType fromApiValue(String value) {
        if (value == null) {
            return MAIL;
        }
        return valueOf(value.trim().toUpperCase());
    }
}
