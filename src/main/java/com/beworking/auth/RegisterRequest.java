package com.beworking.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class RegisterRequest {
    @NotBlank(message = "Email is required")
    @Email(message = "Invalid email format")
    private String email;

    @NotBlank(message = "Name is required")
    private String name;

    @NotBlank(message = "Password is required")
    @Size(min = 8, max = 64, message = "Password must be 8-64 characters")
    private String password;

    private String turnstileToken;

    // Optional fields for trial registration flow
    private String phone;
    private String company;
    private String taxId;
    private String taxIdType;     // Stripe-style: es_cif | es_nif | eu_vat | no_vat
    private String plan;          // "basis", "pro", or "max"
    private String setupIntentId; // Stripe SetupIntent ID
    private String stripeCustomerId; // Stripe Customer ID
    private String location;         // "malaga" or "sevilla"

    public RegisterRequest() {

    }
    public RegisterRequest(String email, String name, String password) {
        this.email = email;
        this.name = name;
        this.password = password;
    }

    public String getEmail() {
        return email;
    }
    public void setEmail(String email) {
        this.email = email;
    }
    public String getName() {
        return name;
    }
    public void setName(String name) {
        this.name = name;
    }

    public String getPassword() {
        return password;
    }
    public void setPassword(String password) {
        this.password = password;
    }

    public String getTurnstileToken() { return turnstileToken; }
    public void setTurnstileToken(String turnstileToken) { this.turnstileToken = turnstileToken; }

    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }

    public String getCompany() { return company; }
    public void setCompany(String company) { this.company = company; }

    public String getTaxId() { return taxId; }
    public void setTaxId(String taxId) { this.taxId = taxId; }
    public String getTaxIdType() { return taxIdType; }
    public void setTaxIdType(String taxIdType) { this.taxIdType = taxIdType; }

    public String getPlan() { return plan; }
    public void setPlan(String plan) { this.plan = plan; }

    public String getSetupIntentId() { return setupIntentId; }
    public void setSetupIntentId(String setupIntentId) { this.setupIntentId = setupIntentId; }

    public String getStripeCustomerId() { return stripeCustomerId; }
    public void setStripeCustomerId(String stripeCustomerId) { this.stripeCustomerId = stripeCustomerId; }

    public String getLocation() { return location; }
    public void setLocation(String location) { this.location = location; }
}
