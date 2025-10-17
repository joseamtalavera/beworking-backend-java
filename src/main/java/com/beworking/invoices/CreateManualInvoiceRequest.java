package com.beworking.invoices;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public class CreateManualInvoiceRequest {

    @NotBlank
    private String clientName;
    
    private Long clientId;
    
    private String userType;
    
    private String center;
    
    private String cuenta;
    
    private String invoiceNum;
    
    @NotNull
    private LocalDate date;
    
    private LocalDate dueDate;
    
    @NotBlank
    private String status;
    
    private String note;
    
    @Valid
    @NotNull
    private List<LineItem> lineItems;
    
    private ComputedTotals computed;

    // Getters and Setters
    public String getClientName() {
        return clientName;
    }

    public void setClientName(String clientName) {
        this.clientName = clientName;
    }

    public Long getClientId() {
        return clientId;
    }

    public void setClientId(Long clientId) {
        this.clientId = clientId;
    }

    public String getUserType() {
        return userType;
    }

    public void setUserType(String userType) {
        this.userType = userType;
    }

    public String getCenter() {
        return center;
    }

    public void setCenter(String center) {
        this.center = center;
    }

    public String getCuenta() {
        return cuenta;
    }

    public void setCuenta(String cuenta) {
        this.cuenta = cuenta;
    }

    public String getInvoiceNum() {
        return invoiceNum;
    }

    public void setInvoiceNum(String invoiceNum) {
        this.invoiceNum = invoiceNum;
    }

    public LocalDate getDate() {
        return date;
    }

    public void setDate(LocalDate date) {
        this.date = date;
    }

    public LocalDate getDueDate() {
        return dueDate;
    }

    public void setDueDate(LocalDate dueDate) {
        this.dueDate = dueDate;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }

    public List<LineItem> getLineItems() {
        return lineItems;
    }

    public void setLineItems(List<LineItem> lineItems) {
        this.lineItems = lineItems;
    }

    public ComputedTotals getComputed() {
        return computed;
    }

    public void setComputed(ComputedTotals computed) {
        this.computed = computed;
    }

    // Inner classes for line items and computed totals
    public static class LineItem {
        @NotBlank
        private String description;
        
        @NotNull
        private BigDecimal quantity;
        
        @NotNull
        private BigDecimal price;
        
        @NotNull
        private BigDecimal vatPercent;

        // Getters and Setters
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

        public BigDecimal getVatPercent() {
            return vatPercent;
        }

        public void setVatPercent(BigDecimal vatPercent) {
            this.vatPercent = vatPercent;
        }
    }

    public static class ComputedTotals {
        private BigDecimal subtotal;
        private BigDecimal totalVat;
        private BigDecimal total;

        // Getters and Setters
        public BigDecimal getSubtotal() {
            return subtotal;
        }

        public void setSubtotal(BigDecimal subtotal) {
            this.subtotal = subtotal;
        }

        public BigDecimal getTotalVat() {
            return totalVat;
        }

        public void setTotalVat(BigDecimal totalVat) {
            this.totalVat = totalVat;
        }

        public BigDecimal getTotal() {
            return total;
        }

        public void setTotal(BigDecimal total) {
            this.total = total;
        }
    }
}
