package com.beworking.invoices;

import java.math.BigDecimal;
import java.sql.Timestamp;

public interface InvoiceProjection {
    Long getId();
    Integer getIdfactura();
    Long getIdcliente();
    Integer getIdcentro();
    String getDescripcion();
    BigDecimal getTotal();
    Integer getIva();
    BigDecimal getTotaliva();
    String getEstado();
    Timestamp getCreated_at();
    String getHoldedinvoicenum();
    String getHoldedinvoicepdf();
}


