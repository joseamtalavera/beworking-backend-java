package com.beworking.invoices;

import jakarta.validation.constraints.NotEmpty;
import java.math.BigDecimal;
import java.util.List;

public class CreateInvoiceRequest {

    @NotEmpty
    private List<Long> bloqueoIds;

    private BigDecimal vatPercent;

    private String description;

    private String reference;

    public List<Long> getBloqueoIds() {
        return bloqueoIds;
    }

    public void setBloqueoIds(List<Long> bloqueoIds) {
        this.bloqueoIds = bloqueoIds;
    }

    public BigDecimal getVatPercent() {
        return vatPercent;
    }

    public void setVatPercent(BigDecimal vatPercent) {
        this.vatPercent = vatPercent;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getReference() {
        return reference;
    }

    public void setReference(String reference) {
        this.reference = reference;
    }
}
