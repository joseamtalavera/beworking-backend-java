package com.beworking.invoices;

/**
 * Invoice category — a machine-readable type used by the dashboard Overview
 * stat cards. Stored on {@code beworking.facturas.category}.
 *
 * <p>Before this existed the cards classified invoices by string-matching room
 * codes in the description, so manually-typed invoices fell into no card at all.
 */
public final class InvoiceCategory {

    public static final String MEETING_ROOM = "meeting_room";
    public static final String COWORKING = "coworking";
    public static final String VIRTUAL_OFFICE = "virtual_office";
    public static final String OTHER = "other";

    private InvoiceCategory() {
    }

    /** Maps a product type ({@code productos.tipo}) to an invoice category. */
    public static String fromProductTipo(String tipo) {
        if (tipo == null) {
            return OTHER;
        }
        switch (tipo.trim().toLowerCase()) {
            case "aula":            return MEETING_ROOM;
            case "mesa":            return COWORKING;
            case "oficina virtual": return VIRTUAL_OFFICE;
            default:                return OTHER;
        }
    }
}
