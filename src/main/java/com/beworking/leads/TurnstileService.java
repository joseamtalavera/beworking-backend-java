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
    private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(TurnstileService.class);

    /**
     * Service for verifying Cloudflare Turnstile tokens.
     *
     * <p>Expects {@code turnstile.secret} to be configured via environment or secrets.
     */
    // TURNSTILE: secret loaded from environment
    @Value("${turnstile.secret:}")
    private String secret;

    /**
     * Verify a Turnstile token with Cloudflare.
     *
     * @param token    the client-provided Turnstile token.
     * @param remoteIp optional client IP address for verification context.
     * @return the verification result, including success/skip flags and message.
     */
    public VerificationResult verify(String token, String remoteIp) {
        logger.info("🔍 Starting Turnstile verification - Secret configured: {}", secret != null && !secret.isBlank());
        
        // If secret missing, skip verification (so dev still works)
        if (secret == null || secret.isBlank()) {
            logger.warn("⚠️ Turnstile secret not configured - skipping verification");
            return VerificationResult.skip("Turnstile secret not configured");
        }
        if (token == null || token.isBlank()) {
            logger.warn("⚠️ Turnstile token is missing");
            return VerificationResult.fail("Missing token");
        }
        
        logger.info("📤 Sending verification request to Cloudflare (token length: {})", token.length());

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
            logger.error("❌ Cloudflare returned empty response");
            return VerificationResult.fail("Empty response");
        }
        
        logger.info("📥 Cloudflare verification response - Success: {}", payload.success);
        if (payload.errorCodes != null && !payload.errorCodes.isEmpty()) {
            logger.warn("⚠️ Cloudflare error codes: {}", payload.errorCodes);
        }
        
        if (payload.success) {
            logger.info("✅ Turnstile verification successful");
            return VerificationResult.success();
        }

        String errors = payload.errorCodes == null ? "" : String.join(",", payload.errorCodes);
        logger.error("❌ Turnstile verification failed: {}", errors);
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
