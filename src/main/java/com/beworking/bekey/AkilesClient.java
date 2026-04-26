package com.beworking.bekey;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import java.util.Map;

/**
 * Thin wrapper over the Akiles HTTP API (https://api.akiles.app/v2).
 * Auth header format is configurable since the public docs don't pin it down —
 * once we have a working token we'll lock the properties in.
 */
@Service
public class AkilesClient {

    private static final Logger LOGGER = LoggerFactory.getLogger(AkilesClient.class);

    private final RestClient http;
    private final String authHeaderName;
    private final String authHeaderValue;

    public AkilesClient(
            @Value("${akiles.api.base-url:https://api.akiles.app/v2}") String baseUrl,
            @Value("${akiles.api.token:}") String token,
            @Value("${akiles.api.auth-scheme:Bearer}") String authScheme,
            @Value("${akiles.api.auth-header-name:Authorization}") String authHeaderName) {
        this.http = RestClient.builder().baseUrl(baseUrl).build();
        this.authHeaderName = authHeaderName;
        this.authHeaderValue = (authScheme == null || authScheme.isBlank())
                ? token
                : authScheme.trim() + " " + token;
        LOGGER.info("AkilesClient initialized: baseUrl={}, header={}, scheme='{}', tokenPresent={}",
                baseUrl, authHeaderName, authScheme, !token.isBlank());
    }

    /** GET /organization — returns the org tied to the API token. Used as a health check. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getOrganization() {
        return http.get()
                .uri("/organization")
                .header(authHeaderName, authHeaderValue)
                .retrieve()
                .body(Map.class);
    }
}
