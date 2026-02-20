package com.beworking.subscriptions;

import java.math.BigDecimal;
import java.time.LocalDate;

public class UpdateSubscriptionRequest {

    private String cuenta;
    private String description;
    private BigDecimal monthlyAmount;
    private Integer vatPercent;
    private LocalDate endDate;
    private Boolean active;

    public String getCuenta() { return cuenta; }
    public void setCuenta(String cuenta) { this.cuenta = cuenta; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public BigDecimal getMonthlyAmount() { return monthlyAmount; }
    public void setMonthlyAmount(BigDecimal monthlyAmount) { this.monthlyAmount = monthlyAmount; }

    public Integer getVatPercent() { return vatPercent; }
    public void setVatPercent(Integer vatPercent) { this.vatPercent = vatPercent; }

    public LocalDate getEndDate() { return endDate; }
    public void setEndDate(LocalDate endDate) { this.endDate = endDate; }

    public Boolean getActive() { return active; }
    public void setActive(Boolean active) { this.active = active; }
}
