package com.beworking.invoices;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public record CreateInvoiceResponse(
    Long id,
    Integer legacyNumber,
    Long clientId,
    Integer centerId,
    String description,
    BigDecimal subtotal,
    BigDecimal vatPercent,
    BigDecimal vatAmount,
    BigDecimal total,
    String status,
    LocalDateTime issuedAt,
    List<Line> lines
) {
    public record Line(
        Long bloqueoId,
        Long reservaId,
        String concept,
        BigDecimal quantity,
        BigDecimal unitPrice,
        BigDecimal total
    ) { }
}
