package com.beworking.contacts;

/**
 * Thrown when an attempt is made to delete a contact profile that still has
 * invoices (beworking.facturas) linked to it. Invoices are legal/accounting
 * records — deleting the contact (and its linked user) would orphan them, so
 * the deletion is blocked entirely. Surfaces as HTTP 409 to the caller.
 */
public class ContactHasInvoicesException extends RuntimeException {

    private final long invoiceCount;

    public ContactHasInvoicesException(Long contactId, long invoiceCount) {
        super("Contact " + contactId + " has " + invoiceCount
            + " invoice(s) linked and cannot be deleted.");
        this.invoiceCount = invoiceCount;
    }

    public long getInvoiceCount() {
        return invoiceCount;
    }
}
