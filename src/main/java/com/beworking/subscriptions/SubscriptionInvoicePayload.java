package com.beworking.subscriptions;

public class SubscriptionInvoicePayload {

    private String stripeSubscriptionId;
    private String stripeCustomerId;
    private String stripeInvoiceId;
    private String stripePaymentIntentId;
    private String customerEmail;
    private Integer amountPaidCents;
    private Integer subtotalCents;
    private Integer taxCents;
    private String invoicePdf;
    private String currency;
    private String periodStart;
    private String periodEnd;
    private String status; // "paid", "pending", or "failed"

    public String getStripeSubscriptionId() { return stripeSubscriptionId; }
    public void setStripeSubscriptionId(String stripeSubscriptionId) { this.stripeSubscriptionId = stripeSubscriptionId; }

    public String getStripeCustomerId() { return stripeCustomerId; }
    public void setStripeCustomerId(String stripeCustomerId) { this.stripeCustomerId = stripeCustomerId; }

    public String getStripeInvoiceId() { return stripeInvoiceId; }
    public void setStripeInvoiceId(String stripeInvoiceId) { this.stripeInvoiceId = stripeInvoiceId; }

    public String getStripePaymentIntentId() { return stripePaymentIntentId; }
    public void setStripePaymentIntentId(String stripePaymentIntentId) { this.stripePaymentIntentId = stripePaymentIntentId; }

    public String getCustomerEmail() { return customerEmail; }
    public void setCustomerEmail(String customerEmail) { this.customerEmail = customerEmail; }

    public Integer getAmountPaidCents() { return amountPaidCents; }
    public void setAmountPaidCents(Integer amountPaidCents) { this.amountPaidCents = amountPaidCents; }

    public Integer getSubtotalCents() { return subtotalCents; }
    public void setSubtotalCents(Integer subtotalCents) { this.subtotalCents = subtotalCents; }

    public Integer getTaxCents() { return taxCents; }
    public void setTaxCents(Integer taxCents) { this.taxCents = taxCents; }

    public String getInvoicePdf() { return invoicePdf; }
    public void setInvoicePdf(String invoicePdf) { this.invoicePdf = invoicePdf; }

    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }

    public String getPeriodStart() { return periodStart; }
    public void setPeriodStart(String periodStart) { this.periodStart = periodStart; }

    public String getPeriodEnd() { return periodEnd; }
    public void setPeriodEnd(String periodEnd) { this.periodEnd = periodEnd; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
}
