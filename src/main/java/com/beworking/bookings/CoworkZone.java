package com.beworking.bookings;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Declarative registry of coworking desk zones. A "zone" is a block of
 * individually-bookable desks whose product names share a prefix
 * ({@code MA1O1-1}, {@code MA1O1-2}, ...). Each zone maps to one BeKey/Akiles
 * member group (its door) and an optional active window.
 *
 * <p>Centralising this here keeps the desk system from being hardcoded to the
 * original MA1O1 room. Adding a zone (e.g. the summer A5 pop-up) is a single
 * entry plus the matching {@code MA1O5-N} product rows (see V93).</p>
 *
 * <p>Mirror of the frontend {@code coworkZones} config — keep the two in sync.</p>
 */
public final class CoworkZone {

    /** Product-name prefix, e.g. "MA1O1-". Desk products are {@code prefix + n}. */
    public final String prefix;
    /** BeKey member-group label (the door), e.g. "MA1O1" or "MA1A5". */
    public final String akilesGroup;
    /** Catalog room code, e.g. "MA1-DESKS". */
    public final String roomCode;
    /** Number of desks in the zone. */
    public final int deskCount;
    /** First day the zone is bookable; null = always. */
    public final LocalDate activeFrom;
    /** Last day the zone is bookable (inclusive); null = always. */
    public final LocalDate activeTo;
    /**
     * Whether a single day booking (bloqueo) on this zone should grant the door
     * for that day. False for the standing-subscription zones (MA1O1) where desk
     * access comes from the sub/membership grant, not per-day; true for day-rate
     * zones (the summer A5 pop-up) where each booked day must open the door.
     */
    public final boolean dayBookingDoor;

    private CoworkZone(String prefix, String akilesGroup, String roomCode, int deskCount,
                       LocalDate activeFrom, LocalDate activeTo, boolean dayBookingDoor) {
        this.prefix = prefix;
        this.akilesGroup = akilesGroup;
        this.roomCode = roomCode;
        this.deskCount = deskCount;
        this.activeFrom = activeFrom;
        this.activeTo = activeTo;
        this.dayBookingDoor = dayBookingDoor;
    }

    /** All known zones. Order matters for display (primary zone first). */
    public static final List<CoworkZone> ALL = List.of(
        new CoworkZone("MA1O1-", "MA1O1", "MA1-DESKS", 16, null, null, false),
        // Summer pop-up: Sala MA1A5 -> 14 desks, reuses the MA1A5 door, Jul–Aug 2026.
        new CoworkZone("MA1O5-", "MA1A5", "MA1A5-DESKS", 14,
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 8, 31), true)
    );

    /** True iff the zone is bookable on the given day. */
    public boolean isActiveOn(LocalDate day) {
        if (day == null) return false;
        if (activeFrom != null && day.isBefore(activeFrom)) return false;
        if (activeTo != null && day.isAfter(activeTo)) return false;
        return true;
    }

    /** The zone a desk product belongs to (by name prefix), if any. */
    public static Optional<CoworkZone> forProductName(String productName) {
        if (productName == null) return Optional.empty();
        String name = productName.trim().toUpperCase();
        return ALL.stream().filter(z -> name.startsWith(z.prefix.toUpperCase())).findFirst();
    }

    /**
     * The BeKey member-group label for a desk product name, or null if the name
     * isn't a desk product in any zone. Used to grant the right door for a desk
     * subscription / day booking regardless of which zone it lives in.
     */
    public static String akilesGroupForProduct(String productName) {
        return forProductName(productName).map(z -> z.akilesGroup).orElse(null);
    }

    /**
     * The bookable-window end for a desk product's zone, or null if the product
     * isn't zoned or its zone has no end. Used to auto-terminate seasonal desk
     * subscriptions (e.g. the summer MA1O5 zone ends 2026-08-31).
     */
    public static LocalDate seasonEndForProduct(String productName) {
        return forProductName(productName).map(z -> z.activeTo).orElse(null);
    }
}
