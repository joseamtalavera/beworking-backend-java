package com.beworking.auth;

public class AccountSummaryDTO {
    private Long id;
    private String companyName;
    private String billingTaxId;
    private String tenantType;

    public AccountSummaryDTO() {}

    public AccountSummaryDTO(Long id, String companyName, String billingTaxId, String tenantType) {
        this.id = id;
        this.companyName = companyName;
        this.billingTaxId = billingTaxId;
        this.tenantType = tenantType;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getCompanyName() { return companyName; }
    public void setCompanyName(String companyName) { this.companyName = companyName; }

    public String getBillingTaxId() { return billingTaxId; }
    public void setBillingTaxId(String billingTaxId) { this.billingTaxId = billingTaxId; }

    public String getTenantType() { return tenantType; }
    public void setTenantType(String tenantType) { this.tenantType = tenantType; }
}
