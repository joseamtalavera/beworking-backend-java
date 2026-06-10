package com.beworking.bekey;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Thin wrapper over the Akiles HTTP API (https://api.akiles.app/v2).
 * Token comes from a TokenProvider — either StaticTokenProvider (production
 * static API key) or RefreshingOAuthTokenProvider (staging OAuth refresh).
 */
@Service
public class AkilesClient {

    private static final Logger LOGGER = LoggerFactory.getLogger(AkilesClient.class);

    private final RestClient http;
    private final TokenProvider tokenProvider;
    private final String authHeaderName;
    private final String authScheme;

    public AkilesClient(
            TokenProvider tokenProvider,
            @Value("${akiles.api.base-url:https://api.akiles.app/v2}") String baseUrl,
            @Value("${akiles.api.auth-scheme:Bearer}") String authScheme,
            @Value("${akiles.api.auth-header-name:Authorization}") String authHeaderName) {
        this.http = RestClient.builder().baseUrl(baseUrl).build();
        this.tokenProvider = tokenProvider;
        this.authHeaderName = authHeaderName;
        this.authScheme = authScheme;
        LOGGER.info("AkilesClient initialized: baseUrl={}, header={}, scheme='{}', tokenProvider={}",
                baseUrl, authHeaderName, authScheme, tokenProvider.getClass().getSimpleName());
    }

    private String authHeaderValue() {
        String token = tokenProvider.token();
        return (authScheme == null || authScheme.isBlank())
                ? token
                : authScheme.trim() + " " + token;
    }

    /** GET /organization — returns the org tied to the current token. Used as a health check. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getOrganization() {
        return http.get()
                .uri("/organization")
                .header(authHeaderName, authHeaderValue())
                .retrieve()
                .body(Map.class);
    }

    /** POST /members — creates an Akiles member. starts_at/ends_at may be null for unbounded. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> createMember(String name, Instant startsAt, Instant endsAt) {
        Map<String, Object> body = new HashMap<>();
        body.put("name", name);
        if (startsAt != null) body.put("starts_at", startsAt.toString());
        if (endsAt != null) body.put("ends_at", endsAt.toString());
        return http.post()
                .uri("/members")
                .header(authHeaderName, authHeaderValue())
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .body(body)
                .retrieve()
                .body(Map.class);
    }

    /** POST /members/{id}/group_associations — adds a member to a member group. Returns the association. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> addGroupAssociation(String memberId, String memberGroupId,
                                                   Instant startsAt, Instant endsAt) {
        Map<String, Object> body = new HashMap<>();
        body.put("member_group_id", memberGroupId);
        if (startsAt != null) body.put("starts_at", startsAt.toString());
        if (endsAt != null) body.put("ends_at", endsAt.toString());
        return http.post()
                .uri("/members/{id}/group_associations", memberId)
                .header(authHeaderName, authHeaderValue())
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .body(body)
                .retrieve()
                .body(Map.class);
    }

    /** DELETE /members/{id}/group_associations/{assoc_id} — removes a member from a member group. */
    public void removeGroupAssociation(String memberId, String associationId) {
        http.delete()
                .uri("/members/{id}/group_associations/{assocId}", memberId, associationId)
                .header(authHeaderName, authHeaderValue())
                .retrieve()
                .toBodilessEntity();
    }

    /** GET /members/{id}/group_associations — lists current associations for a member. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> listGroupAssociations(String memberId) {
        return http.get()
                .uri("/members/{id}/group_associations", memberId)
                .header(authHeaderName, authHeaderValue())
                .retrieve()
                .body(Map.class);
    }

    /**
     * POST /gadgets/{gadgetId}/actions/{actionId} — actuates a gadget (opens a door). Body is empty.
     * When the physical hardware is unreachable Akiles answers with an error whose
     * type/message mentions "offline" — we translate that into a typed
     * {@link BeKeyHardwareOfflineException} so the door-open endpoint can return a
     * friendly "device offline" response instead of a generic 500.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> doGadgetAction(String gadgetId, String actionId) {
        try {
            return http.post()
                    .uri("/gadgets/{gadgetId}/actions/{actionId}", gadgetId, actionId)
                    .header(authHeaderName, authHeaderValue())
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .body(Map.of())
                    .retrieve()
                    .body(Map.class);
        } catch (RestClientResponseException e) {
            String body = e.getResponseBodyAsString();
            if (body != null && body.toLowerCase().contains("offline")) {
                LOGGER.warn("Akiles gadget {} offline: {}", gadgetId, body);
                throw new BeKeyHardwareOfflineException("Door hardware is offline");
            }
            throw e;
        }
    }

    /** GET /members/{id}/pins — lists a member's PIN credentials (values masked; use revealMemberPin to read). */
    @SuppressWarnings("unchecked")
    public Map<String, Object> listMemberPins(String memberId) {
        return http.get()
                .uri("/members/{id}/pins", memberId)
                .header(authHeaderName, authHeaderValue())
                .retrieve()
                .body(Map.class);
    }

    /** POST /members/{id}/pins/{pinId}/reveal — returns the cleartext PIN value. Body is empty. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> revealMemberPin(String memberId, String pinId) {
        return http.post()
                .uri("/members/{id}/pins/{pinId}/reveal", memberId, pinId)
                .header(authHeaderName, authHeaderValue())
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .body(Map.of())
                .retrieve()
                .body(Map.class);
    }

    /** GET /member_groups/{id} — returns the group incl. its permission rules (which gadgets it opens). */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getMemberGroup(String memberGroupId) {
        return http.get()
                .uri("/member_groups/{id}", memberGroupId)
                .header(authHeaderName, authHeaderValue())
                .retrieve()
                .body(Map.class);
    }
}
