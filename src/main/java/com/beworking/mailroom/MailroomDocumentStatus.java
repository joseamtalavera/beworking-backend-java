package com.beworking.mailroom;

/**
 * Enumerates the lifecycle states for a mailroom document.
 */
public enum MailroomDocumentStatus {
    SCANNED,
    NOTIFIED,
    VIEWED,
    PICKED_UP;

    public String toApiValue() {
        return name().toLowerCase();
    }
}
