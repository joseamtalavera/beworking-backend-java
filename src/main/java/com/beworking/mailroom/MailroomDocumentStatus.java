package com.beworking.mailroom;

/**
 * Enumerates the lifecycle states for a mailroom document.
 */
public enum MailroomDocumentStatus {
    SCANNED,
    NOTIFIED,
    VIEWED;

    public String toApiValue() {
        return name().toLowerCase();
    }
}
