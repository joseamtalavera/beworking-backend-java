package com.beworking.contacts;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

@Entity
@Table(name = "contact_profiles", schema = "beworking")
public class ContactProfile {

    @Id
    private Long id;

    private String name;

    @Column(name = "contact_name")
    private String contactName;

    @Column(name = "phone_primary")
    private String phonePrimary;

    @Column(name = "phone_secondary")
    private String phoneSecondary;

    @Column(name = "phone_tertiary")
    private String phoneTertiary;

    @Column(name = "phone_quaternary")
    private String phoneQuaternary;

    @Column(name = "email_primary")
    private String emailPrimary;

    @Column(name = "email_secondary")
    private String emailSecondary;

    @Column(name = "email_tertiary")
    private String emailTertiary;

    @Column(name = "billing_name")
    private String billingName;

    @Column(name = "billing_tax_id")
    private String billingTaxId;

    @Column(name = "billing_address")
    private String billingAddress;

    @Column(name = "billing_city")
    private String billingCity;

    @Column(name = "billing_province")
    private String billingProvince;

    @Column(name = "billing_country")
    private String billingCountry;

    @Column(name = "billing_postal_code")
    private String billingPostalCode;

    @Column(name = "representative_first_name")
    private String representativeFirstName;

    @Column(name = "representative_last_name")
    private String representativeLastName;

    @Column(name = "representative_email")
    private String representativeEmail;

    @Column(name = "representative_phone")
    private String representativePhone;

    @Column(name = "tenant_type")
    private String tenantType;

    private String assignment;

    private String category;

    private String status;

    @Column(name = "is_active")
    private Boolean active;

    private String channel;

    @Column(name = "center_id")
    private Long centerId;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "status_changed_at")
    private LocalDateTime statusChangedAt;

    @Column(name = "onboarded_at")
    private LocalDateTime onboardedAt;

    @Column(name = "avatar")
    private String avatar;

    public ContactProfile() {
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getContactName() {
        return contactName;
    }

    public void setContactName(String contactName) {
        this.contactName = contactName;
    }

    public String getPhonePrimary() {
        return phonePrimary;
    }

    public void setPhonePrimary(String phonePrimary) {
        this.phonePrimary = phonePrimary;
    }

    public String getPhoneSecondary() {
        return phoneSecondary;
    }

    public void setPhoneSecondary(String phoneSecondary) {
        this.phoneSecondary = phoneSecondary;
    }

    public String getPhoneTertiary() {
        return phoneTertiary;
    }

    public void setPhoneTertiary(String phoneTertiary) {
        this.phoneTertiary = phoneTertiary;
    }

    public String getPhoneQuaternary() {
        return phoneQuaternary;
    }

    public void setPhoneQuaternary(String phoneQuaternary) {
        this.phoneQuaternary = phoneQuaternary;
    }

    public String getEmailPrimary() {
        return emailPrimary;
    }

    public void setEmailPrimary(String emailPrimary) {
        this.emailPrimary = emailPrimary;
    }

    public String getEmailSecondary() {
        return emailSecondary;
    }

    public void setEmailSecondary(String emailSecondary) {
        this.emailSecondary = emailSecondary;
    }

    public String getEmailTertiary() {
        return emailTertiary;
    }

    public void setEmailTertiary(String emailTertiary) {
        this.emailTertiary = emailTertiary;
    }

    public String getBillingName() {
        return billingName;
    }

    public void setBillingName(String billingName) {
        this.billingName = billingName;
    }

    public String getBillingTaxId() {
        return billingTaxId;
    }

    public void setBillingTaxId(String billingTaxId) {
        this.billingTaxId = billingTaxId;
    }

    public String getBillingAddress() {
        return billingAddress;
    }

    public void setBillingAddress(String billingAddress) {
        this.billingAddress = billingAddress;
    }

    public String getBillingCity() {
        return billingCity;
    }

    public void setBillingCity(String billingCity) {
        this.billingCity = billingCity;
    }

    public String getBillingProvince() {
        return billingProvince;
    }

    public void setBillingProvince(String billingProvince) {
        this.billingProvince = billingProvince;
    }

    public String getBillingCountry() {
        return billingCountry;
    }

    public void setBillingCountry(String billingCountry) {
        this.billingCountry = billingCountry;
    }

    public String getBillingPostalCode() {
        return billingPostalCode;
    }

    public void setBillingPostalCode(String billingPostalCode) {
        this.billingPostalCode = billingPostalCode;
    }

    public String getRepresentativeFirstName() {
        return representativeFirstName;
    }

    public void setRepresentativeFirstName(String representativeFirstName) {
        this.representativeFirstName = representativeFirstName;
    }

    public String getRepresentativeLastName() {
        return representativeLastName;
    }

    public void setRepresentativeLastName(String representativeLastName) {
        this.representativeLastName = representativeLastName;
    }

    public String getRepresentativeEmail() {
        return representativeEmail;
    }

    public void setRepresentativeEmail(String representativeEmail) {
        this.representativeEmail = representativeEmail;
    }

    public String getRepresentativePhone() {
        return representativePhone;
    }

    public void setRepresentativePhone(String representativePhone) {
        this.representativePhone = representativePhone;
    }

    public String getTenantType() {
        return tenantType;
    }

    public void setTenantType(String tenantType) {
        this.tenantType = tenantType;
    }

    public String getAssignment() {
        return assignment;
    }

    public void setAssignment(String assignment) {
        this.assignment = assignment;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Boolean getActive() {
        return active;
    }

    public void setActive(Boolean active) {
        this.active = active;
    }

    public String getChannel() {
        return channel;
    }

    public void setChannel(String channel) {
        this.channel = channel;
    }

    public Long getCenterId() {
        return centerId;
    }

    public void setCenterId(Long centerId) {
        this.centerId = centerId;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getStatusChangedAt() {
        return statusChangedAt;
    }

    public void setStatusChangedAt(LocalDateTime statusChangedAt) {
        this.statusChangedAt = statusChangedAt;
    }

    public LocalDateTime getOnboardedAt() {
        return onboardedAt;
    }

    public void setOnboardedAt(LocalDateTime onboardedAt) {
        this.onboardedAt = onboardedAt;
    }

    public String getAvatar() {
        return avatar;
    }

    public void setAvatar(String avatar) {
        this.avatar = avatar;
    }
}
