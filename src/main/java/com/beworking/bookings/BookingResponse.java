package com.beworking.bookings;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public record BookingResponse(
    Long id,
    Long clientId,
    String clientName,
    String clientEmail,
    String clientTenantType,
    Long centerId,
    String centerCode,
    String centerName,
    Long productId,
    String productName,
    String productType,
    String reservationType,
    LocalDate dateFrom,
    LocalDate dateTo,
    String timeFrom,
    String timeTo,
    Double rate,
    Integer attendees,
    String configuration,
    String notes,
    String status,
    boolean openEnded,
    List<String> days,
    LocalDateTime createdAt,
    LocalDateTime updatedAt
) {}
