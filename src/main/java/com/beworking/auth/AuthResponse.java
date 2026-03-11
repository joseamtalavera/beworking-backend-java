package com.beworking.auth;

import java.util.List;

public class AuthResponse {
    private String message;
    private String token;
    private String role;
    private boolean accountSelectionRequired;
    private String selectionToken;
    private List<AccountSummaryDTO> accounts;

    public AuthResponse() {}

    public AuthResponse(String message, String token, String role) {
        this.message = message;
        this.token = token;
        this.role = role;
    }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public String getToken() { return token; }
    public void setToken(String token) { this.token = token; }

    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }

    public boolean isAccountSelectionRequired() { return accountSelectionRequired; }
    public void setAccountSelectionRequired(boolean accountSelectionRequired) { this.accountSelectionRequired = accountSelectionRequired; }

    public String getSelectionToken() { return selectionToken; }
    public void setSelectionToken(String selectionToken) { this.selectionToken = selectionToken; }

    public List<AccountSummaryDTO> getAccounts() { return accounts; }
    public void setAccounts(List<AccountSummaryDTO> accounts) { this.accounts = accounts; }
}
