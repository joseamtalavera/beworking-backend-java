package com.beworking.contacts;

public class ContactProfileRequest {
    
    private String name;
    private String email;
    
    private String primaryContact;
    private String phone;
    private String status;
    private String userType;
    private String center;
    private String channel;
    
    // Billing information
    private String billingCompany;
    private String billingEmail;
    private String billingAddress;
    private String billingPostalCode;
    private String billingCounty;
    private String billingCountry;
    
    // Constructors
    public ContactProfileRequest() {}
    
    public ContactProfileRequest(String name, String email) {
        this.name = name;
        this.email = email;
    }
    
    // Getters and Setters
    public String getName() {
        return name;
    }
    
    public void setName(String name) {
        this.name = name;
    }
    
    public String getEmail() {
        return email;
    }
    
    public void setEmail(String email) {
        this.email = email;
    }
    
    public String getPrimaryContact() {
        return primaryContact;
    }
    
    public void setPrimaryContact(String primaryContact) {
        this.primaryContact = primaryContact;
    }
    
    public String getPhone() {
        return phone;
    }
    
    public void setPhone(String phone) {
        this.phone = phone;
    }
    
    public String getStatus() {
        return status;
    }
    
    public void setStatus(String status) {
        this.status = status;
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
    
    public String getChannel() {
        return channel;
    }
    
    public void setChannel(String channel) {
        this.channel = channel;
    }
    
    public String getBillingCompany() {
        return billingCompany;
    }
    
    public void setBillingCompany(String billingCompany) {
        this.billingCompany = billingCompany;
    }
    
    public String getBillingEmail() {
        return billingEmail;
    }
    
    public void setBillingEmail(String billingEmail) {
        this.billingEmail = billingEmail;
    }
    
    public String getBillingAddress() {
        return billingAddress;
    }
    
    public void setBillingAddress(String billingAddress) {
        this.billingAddress = billingAddress;
    }
    
    public String getBillingPostalCode() {
        return billingPostalCode;
    }
    
    public void setBillingPostalCode(String billingPostalCode) {
        this.billingPostalCode = billingPostalCode;
    }
    
    public String getBillingCounty() {
        return billingCounty;
    }
    
    public void setBillingCounty(String billingCounty) {
        this.billingCounty = billingCounty;
    }
    
    public String getBillingCountry() {
        return billingCountry;
    }
    
    public void setBillingCountry(String billingCountry) {
        this.billingCountry = billingCountry;
    }
}
