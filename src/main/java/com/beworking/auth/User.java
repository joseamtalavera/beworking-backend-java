package com.beworking.auth;

import jakarta.persistence.*;
import jakarta.persistence.GenerationType;

/**
 * User entity representing application users for multi-tenant, role-based authentication.
 */
@Entity
@Table(name = "users", schema = "beworking")
public class User {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(nullable = false)
    private String password;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role;

    @Column(name = "tenant_id", nullable = true)
    private Long tenantId;

    @Column(name = "email_confirmed", nullable = false)
    private boolean emailConfirmed = false;

    @Column(name = "confirmation_token")
    private String confirmationToken;

    @Column(name = "confirmation_token_expiry")
    private java.time.Instant confirmationTokenExpiry;

    @Column(name = "reset_token")
    private String resetToken;

    @Column(name = "reset_token_expiry")
    private java.time.Instant resetTokenExpiry;

    @Column(name = "avatar")
    private String avatar;

    @Column(name = "name")
    private String name;

    @Column(name = "phone")
    private String phone;

    @Column(name = "address_line1")
    private String addressLine1;

    @Column(name = "address_city")
    private String addressCity;

    @Column(name = "address_country")
    private String addressCountry;

    @Column(name = "address_postal")
    private String addressPostal;

    @Column(name = "billing_brand")
    private String billingBrand;

    @Column(name = "billing_last4")
    private String billingLast4;

    @Column(name = "billing_exp_month")
    private Integer billingExpMonth;

    @Column(name = "billing_exp_year")
    private Integer billingExpYear;

    @Column(name = "stripe_customer_id")
    private String stripeCustomerId;

    public enum Role {
        ADMIN, USER;
        // For future extensibility, you can add more roles here
    }

    // Builder pattern for easier instantiation
    public static class Builder {
        private String email;
        private String password;
        private Role role;
        private Long tenantId;

        public Builder email(String email) {
            this.email = email;
            return this;
        }
        public Builder password(String password) {
            this.password = password;
            return this;
        }
        public Builder role(Role role) {
            this.role = role;
            return this;
        }
        public Builder tenantId(Long tenantId) {
            this.tenantId = tenantId;
            return this;
        }
        public User build() {
            User user = new User(email, password, role);
            user.setTenantId(tenantId);
            return user;
        }
    }

    /**
     * Returns true if the user has ADMIN role.
     */
    public boolean isAdmin() {
        return this.role == Role.ADMIN;
    }
    /**
     * Returns true if the user has USER role.
     */
    public boolean isUser() {
        return this.role == Role.USER;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        User user = (User) o;
        return java.util.Objects.equals(id, user.id) &&
                java.util.Objects.equals(email, user.email);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(id, email);
    }

    @Override
    public String toString() {
        return "User{" +
                "id=" + id +
                ", email='" + email + '\'' +
                ", role=" + role +
                ", tenantId=" + tenantId +
                '}';
    }

    public User() {}

    public User(String email, String password, Role role) {
        this.email = email;
        this.password = password;
        this.role = role;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }

    public Role getRole() { return role; }
    public void setRole(Role role) { this.role = role; }

    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }

    public boolean isEmailConfirmed(){ return emailConfirmed; }
    public void setEmailConfirmed(boolean emailConfirmed) { this.emailConfirmed = emailConfirmed;}

    public String getConfirmationToken() { return confirmationToken;}
    public void setConfirmationToken(String confirmationToken) { this.confirmationToken = confirmationToken;}

    public java.time.Instant getConfirmationTokenExpiry() { return confirmationTokenExpiry;}
    public void setConfirmationTokenExpiry(java.time.Instant confirmationTokenExpiry) { this.confirmationTokenExpiry = confirmationTokenExpiry;}

    public String getResetToken() { return resetToken; }
    public void setResetToken(String resetToken) { this.resetToken = resetToken; }

    public java.time.Instant getResetTokenExpiry() { return resetTokenExpiry; }
    public void setResetTokenExpiry(java.time.Instant resetTokenExpiry) { this.resetTokenExpiry = resetTokenExpiry; }

    public String getAvatar() { return avatar; }
    public void setAvatar(String avatar) { this.avatar = avatar; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }

    public String getAddressLine1() { return addressLine1; }
    public void setAddressLine1(String addressLine1) { this.addressLine1 = addressLine1; }

    public String getAddressCity() { return addressCity; }
    public void setAddressCity(String addressCity) { this.addressCity = addressCity; }

    public String getAddressCountry() { return addressCountry; }
    public void setAddressCountry(String addressCountry) { this.addressCountry = addressCountry; }

    public String getAddressPostal() { return addressPostal; }
    public void setAddressPostal(String addressPostal) { this.addressPostal = addressPostal; }

    public String getBillingBrand() { return billingBrand; }
    public void setBillingBrand(String billingBrand) { this.billingBrand = billingBrand; }

    public String getBillingLast4() { return billingLast4; }
    public void setBillingLast4(String billingLast4) { this.billingLast4 = billingLast4; }

    public Integer getBillingExpMonth() { return billingExpMonth; }
    public void setBillingExpMonth(Integer billingExpMonth) { this.billingExpMonth = billingExpMonth; }

    public Integer getBillingExpYear() { return billingExpYear; }
    public void setBillingExpYear(Integer billingExpYear) { this.billingExpYear = billingExpYear; }

    public String getStripeCustomerId() { return stripeCustomerId; }
    public void setStripeCustomerId(String stripeCustomerId) { this.stripeCustomerId = stripeCustomerId; }
}