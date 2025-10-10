package com.beworking.bookings;

import java.time.LocalDateTime;
import java.util.List;

class BookingConflictException extends RuntimeException {

    private final List<ConflictSlot> conflicts;

    BookingConflictException(String message, List<ConflictSlot> conflicts) {
        super(message);
        this.conflicts = conflicts;
    }

    List<ConflictSlot> getConflicts() {
        return conflicts;
    }

    static ConflictSlot conflict(LocalDateTime start, LocalDateTime end) {
        return new ConflictSlot(start, end);
    }

    record ConflictSlot(LocalDateTime start, LocalDateTime end) { }
}
