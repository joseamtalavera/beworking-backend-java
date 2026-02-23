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

    private List<ExtraLineItem> extraLineItems;

    private String stripeInvoiceId;

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

    public List<ExtraLineItem> getExtraLineItems() {
        return extraLineItems;
    }

    public void setExtraLineItems(List<ExtraLineItem> extraLineItems) {
        this.extraLineItems = extraLineItems;
    }

    public String getStripeInvoiceId() {
        return stripeInvoiceId;
    }

    public void setStripeInvoiceId(String stripeInvoiceId) {
        this.stripeInvoiceId = stripeInvoiceId;
    }

    public static class ExtraLineItem {
        private String description;
        private BigDecimal quantity;
        private BigDecimal price;

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }

        public BigDecimal getQuantity() {
            return quantity;
        }

        public void setQuantity(BigDecimal quantity) {
            this.quantity = quantity;
        }

        public BigDecimal getPrice() {
            return price;
        }

        public void setPrice(BigDecimal price) {
            this.price = price;
        }
    }
}
