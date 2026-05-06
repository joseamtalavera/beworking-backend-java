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
    String avatar,
    Billing billing,
    @JsonProperty("recovery_email_count") Integer recoveryEmailCount,
    @JsonProperty("last_recovery_email_at") String lastRecoveryEmailAt
) {
    public record Contact(String name, String email) {}

    public static record Billing(
        String company,
        String email,
        String address,
        @JsonProperty("postal_code") String postalCode,
        String county,
        String city,
        String country,
        @JsonProperty("tax_id") String taxId,
        @JsonProperty("tax_id_type") String taxIdType,
        @JsonProperty("vat_valid") Boolean vatValid
    ) {}
}
