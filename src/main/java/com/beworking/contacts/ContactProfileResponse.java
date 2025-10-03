package com.beworking.contacts;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ContactProfileResponse(
    Long id,
    String name,
    Contact contact,
    String plan,
    @JsonProperty("center") String center,
    @JsonProperty("user_type") String userType,
    String status,
    Integer seats,
    Double usage,
    String lastActive,
    String channel,
    @JsonProperty("created_at") String createdAt,
    @JsonProperty("phone_primary") String phonePrimary,
    Billing billing
) {
    public record Contact(String name, String email) {}

    public static record Billing(
        String company,
        String email,
        String address,
        @JsonProperty("postal_code") String postalCode,
        String county,
        String country,
        @JsonProperty("tax_id") String taxId
    ) {}
}
