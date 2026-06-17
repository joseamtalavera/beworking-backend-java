package com.beworking.bookings;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.List;

/**
 * Lightweight request for the pre-charge availability check
 * ({@code POST /api/public/bookings/availability}). Carries only the schedule
 * fields needed to detect bloqueo conflicts — no contact/billing data, since the
 * check runs before any Stripe subscription or charge is created (#282).
 */
public class AvailabilityCheckRequest {

    @NotBlank
    private String productName;

    @NotNull
    private LocalDate date;

    private LocalDate dateTo;

    private String startTime;

    private String endTime;

    private List<String> weekdays;

    public String getProductName() { return productName; }
    public void setProductName(String productName) { this.productName = productName; }

    public LocalDate getDate() { return date; }
    public void setDate(LocalDate date) { this.date = date; }

    public LocalDate getDateTo() { return dateTo; }
    public void setDateTo(LocalDate dateTo) { this.dateTo = dateTo; }

    public String getStartTime() { return startTime; }
    public void setStartTime(String startTime) { this.startTime = startTime; }

    public String getEndTime() { return endTime; }
    public void setEndTime(String endTime) { this.endTime = endTime; }

    public List<String> getWeekdays() { return weekdays; }
    public void setWeekdays(List<String> weekdays) { this.weekdays = weekdays; }
}
