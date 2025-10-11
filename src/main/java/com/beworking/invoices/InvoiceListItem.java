package com.beworking.invoices;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record InvoiceListItem(
    Long id,
    Integer idFactura,
    Long idCliente,
    Integer idCentro,
    String descripcion,
    BigDecimal total,
    Integer iva,
    BigDecimal totalIva,
    String estado,
    LocalDateTime createdAt,
    String holdedInvoiceNum,
    String holdedInvoicePdf,
    String clientName,
    String clientEmail,
    String tenantType,
    String products
) { }
