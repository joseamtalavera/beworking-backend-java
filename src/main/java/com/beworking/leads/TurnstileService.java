package com.beworking.leads;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.util.List;

@Service
public class TurnstileService {

    private static final String VERIFY_URL = "https://challenges.cloudflare.com/turnstile/v0/siteverify";

    // TURNSTILE: secret loaded from environment
    @Value("${turnstile.secret:}")
    private String secret;

    public VerificationResult verify(String token, String remoteIp) {
        // If secret missing, skip verification (so dev still works)
        if (secret == null || secret.isBlank()) {
            return VerificationResult.skip("Turnstile secret not configured");
        }
        if (token == null || token.isBlank()) {
            return VerificationResult.fail("Missing token");
        }

        RestTemplate restTemplate = new RestTemplate();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("secret", secret);
        body.add("response", token);
        if (remoteIp != null && !remoteIp.isBlank()) {
            body.add("remoteip", remoteIp);
        }

        HttpEntity<MultiValueMap<String, String>> entity = new HttpEntity<>(body, headers);
        ResponseEntity<TurnstileResponse> response =
            restTemplate.postForEntity(VERIFY_URL, entity, TurnstileResponse.class);

        TurnstileResponse payload = response.getBody();
        if (payload == null) {
            return VerificationResult.fail("Empty response");
        }
        if (payload.success) {
            return VerificationResult.success();
        }

        String errors = payload.errorCodes == null ? "" : String.join(",", payload.errorCodes);
        return VerificationResult.fail(errors);
    }

    // Turnstile response shape
    static class TurnstileResponse {
        public boolean success;

        @JsonProperty("error-codes")
        public List<String> errorCodes;
    }

    // Small wrapper for result status
    public static class VerificationResult {
        private final boolean success;
        private final boolean skipped;
        private final String message;

        private VerificationResult(boolean success, boolean skipped, String message) {
            this.success = success;
            this.skipped = skipped;
            this.message = message;
        }

        public static VerificationResult success() {
            return new VerificationResult(true, false, null);
        }

        public static VerificationResult fail(String message) {
            return new VerificationResult(false, false, message);
        }

        public static VerificationResult skip(String message) {
            return new VerificationResult(true, true, message);
        }

        public boolean isSuccess() {
            return success;
        }

        public boolean isSkipped() {
            return skipped;
        }

        public String getMessage() {
            return message;
        }
    }
}
