package com.beworking.subscriptions;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "subscriptions", schema = "beworking")
public class Subscription {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "contact_id", nullable = false)
    private Long contactId;

    @Column(name = "stripe_subscription_id", unique = true)
    private String stripeSubscriptionId;

    @Column(name = "stripe_customer_id")
    private String stripeCustomerId;

    @Column(name = "monthly_amount", nullable = false, precision = 18, scale = 2)
    private BigDecimal monthlyAmount;

    @Column(name = "currency", length = 3)
    private String currency = "EUR";

    @Column(name = "cuenta", nullable = false, length = 10)
    private String cuenta = "PT";

    @Column(name = "description")
    private String description = "Oficina Virtual";

    @Column(name = "vat_percent")
    private Integer vatPercent = 21;

    @Column(name = "vat_number", length = 50)
    private String vatNumber;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date")
    private LocalDate endDate;

    @Column(name = "active")
    private Boolean active = true;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "billing_method", length = 20)
    private String billingMethod = "stripe";

    @Column(name = "last_invoiced_month", length = 7)
    private String lastInvoicedMonth;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public Subscription() {}

    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }

    public Long getContactId() { return contactId; }
    public void setContactId(Long contactId) { this.contactId = contactId; }

    public String getStripeSubscriptionId() { return stripeSubscriptionId; }
    public void setStripeSubscriptionId(String stripeSubscriptionId) { this.stripeSubscriptionId = stripeSubscriptionId; }

    public String getStripeCustomerId() { return stripeCustomerId; }
    public void setStripeCustomerId(String stripeCustomerId) { this.stripeCustomerId = stripeCustomerId; }

    public BigDecimal getMonthlyAmount() { return monthlyAmount; }
    public void setMonthlyAmount(BigDecimal monthlyAmount) { this.monthlyAmount = monthlyAmount; }

    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }

    public String getCuenta() { return cuenta; }
    public void setCuenta(String cuenta) { this.cuenta = cuenta; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public Integer getVatPercent() { return vatPercent; }
    public void setVatPercent(Integer vatPercent) { this.vatPercent = vatPercent; }

    public String getVatNumber() { return vatNumber; }
    public void setVatNumber(String vatNumber) { this.vatNumber = vatNumber; }

    public LocalDate getStartDate() { return startDate; }
    public void setStartDate(LocalDate startDate) { this.startDate = startDate; }

    public LocalDate getEndDate() { return endDate; }
    public void setEndDate(LocalDate endDate) { this.endDate = endDate; }

    public Boolean getActive() { return active; }
    public void setActive(Boolean active) { this.active = active; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    public String getBillingMethod() { return billingMethod; }
    public void setBillingMethod(String billingMethod) { this.billingMethod = billingMethod; }

    public String getLastInvoicedMonth() { return lastInvoicedMonth; }
    public void setLastInvoicedMonth(String lastInvoicedMonth) { this.lastInvoicedMonth = lastInvoicedMonth; }
}
