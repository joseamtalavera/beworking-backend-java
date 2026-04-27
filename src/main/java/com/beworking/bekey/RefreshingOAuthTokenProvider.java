package com.beworking.bekey;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.Base64;

/**
 * Staging/dev token provider: maintains a cached access_token, refreshes it
 * via the OAuth refresh_token flow before expiry. Active when
 * akiles.auth.mode=oauth.
 */
@Component
@ConditionalOnProperty(name = "akiles.auth.mode", havingValue = "oauth")
class RefreshingOAuthTokenProvider implements TokenProvider {

    private static final Logger LOGGER = LoggerFactory.getLogger(RefreshingOAuthTokenProvider.class);
    private static final long REFRESH_BUFFER_SECONDS = 60;

    private final RestClient http;
    private final String clientId;
    private final String clientSecret;

    private String refreshToken;
    private String cachedAccessToken;
    private Instant accessTokenExpiresAt = Instant.EPOCH;

    RefreshingOAuthTokenProvider(
            @Value("${akiles.oauth.token-url:https://auth.akiles.app/oauth2/token}") String tokenUrl,
            @Value("${akiles.oauth.client-id}") String clientId,
            @Value("${akiles.oauth.client-secret}") String clientSecret,
            @Value("${akiles.oauth.refresh-token}") String refreshToken) {
        this.http = RestClient.builder().baseUrl(tokenUrl).build();
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.refreshToken = refreshToken;
        LOGGER.info("RefreshingOAuthTokenProvider initialized: tokenUrl={}, clientId={}",
                tokenUrl, clientId);
    }

    @Override
    public synchronized String token() {
        if (cachedAccessToken == null
                || Instant.now().isAfter(accessTokenExpiresAt.minusSeconds(REFRESH_BUFFER_SECONDS))) {
            refresh();
        }
        return cachedAccessToken;
    }

    private void refresh() {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "refresh_token");
        form.add("refresh_token", refreshToken);

        String basicAuth = Base64.getEncoder().encodeToString(
                (clientId + ":" + clientSecret).getBytes());

        JsonNode response = http.post()
                .header(HttpHeaders.AUTHORIZATION, "Basic " + basicAuth)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(JsonNode.class);

        if (response == null || !response.has("access_token")) {
            throw new IllegalStateException("Akiles token refresh returned no access_token: " + response);
        }

        cachedAccessToken = response.get("access_token").asText();
        long expiresIn = response.has("expires_in") ? response.get("expires_in").asLong() : 3600;
        accessTokenExpiresAt = Instant.now().plusSeconds(expiresIn);

        if (response.has("refresh_token")) {
            refreshToken = response.get("refresh_token").asText();
        }

        LOGGER.info("Akiles token refreshed: expires_in={}s", expiresIn);
    }
}
