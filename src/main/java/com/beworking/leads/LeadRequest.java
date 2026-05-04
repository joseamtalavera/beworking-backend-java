package com.beworking.leads;

import jakarta.validation.constraints.*;

public class LeadRequest {
    @NotBlank(message = "El nombre es obligatorio")
    @Size(max = 50, message = "El nombre es demasiado largo")
    private String name;

    // Phone is now optional. The OV-interest forms still send it; the
    // generic /contact form does not. Empty string is allowed; if present,
    // must match the legacy phone format.
    @Pattern(regexp = "^$|^[0-9 +()\\-]{7,15}$", message = "El teléfono es inválido")
    private String phone;

    @NotBlank(message = "El email es obligatorio")
    @Email(message = "Formato de email inválido")
    @Size(max = 100, message = "El email es demasiado largo")
    private String email;

    @Size(max = 120, message = "El asunto es demasiado largo")
    private String subject;

    @Size(max = 5000, message = "El mensaje es demasiado largo")
    private String message;

    @Size(max = 40)
    private String source;

    // TURNSTILE/HONEYPOT: optional fields for anti-spam
    private String company; // honeypot
    private String turnstileToken;

    // Getters and setters
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getSubject() { return subject; }
    public void setSubject(String subject) { this.subject = subject; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }

    // TURNSTILE/HONEYPOT: getters/setters
    public String getCompany() { return company; }
    public void setCompany(String company) { this.company = company;}
    public String getTurnstileToken() { return turnstileToken;}
    public void setTurnstileToken(String turnstileToken) { this.turnstileToken = turnstileToken; }
}
