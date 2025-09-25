
package com.beworking.integrations.hubspot; // Import for Hubspot integration service

import com.beworking.leads.Lead; 
import com.beworking.leads.LeadRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger; 
import org.slf4j.LoggerFactory; 
import org.springframework.beans.factory.annotation.Value; // Import for Value annotation to inject properties from application.properties
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
@Service
public class HubspotService {
    private static final Logger log = LoggerFactory.getLogger(HubspotService.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final LeadRepository leadRepository;

    @Value("${hubspot.api.baseUrl:https://api.hubapi.com}")
    private String hubspotBaseUrl;
    @Value("${hubspot.api.token:${beworking.api.token:}}")
    private String hubspotToken;

    public HubspotService(LeadRepository leadRepository) {
        this.leadRepository = leadRepository;
    }
    
    //Sync a lead with HubSpot. Return a HubspotSyncResult with status, id, and error message if any.

    public HubspotSyncResult syncLead(Lead lead) {
        log.info("HubspotService.synclead stub called for lead id={}", lead.getId());
        if (log.isDebugEnabled()) {
            log.debug("HubSpot config → baseUrl={}, tokenPrefix={}", hubspotBaseUrl,
                hubspotToken != null && hubspotToken.length() >= 8 ? hubspotToken.substring(0, 8) : "(none)");
        }
        try {
            if (hubspotToken == null || hubspotToken.isBlank()) {
                String message = "Missing HubSpot API token. Set hubspot.api.token or HUBSPOT_API_TOKEN.";
                log.warn(message);
                return new HubspotSyncResult(false, null, message);
            }
            RestTemplate restTemplate = new RestTemplate();
            String url = hubspotBaseUrl + "/crm/v3/objects/contacts";

            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("properties", Map.of(
                "email", lead.getEmail(),
                "firstname", lead.getName(),
                "phone", lead.getPhone()
            ));
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(hubspotToken);

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);
            ResponseEntity<Map<String, Object>> response = restTemplate.postForEntity(url, entity, (Class<Map<String, Object>>) (Class<?>) Map.class);

            if (response.getStatusCode().is2xxSuccessful()) {
                Object id = response.getBody().get("id");
                return new HubspotSyncResult(true, id != null ? id.toString() : null, null);
            }else {
                return new HubspotSyncResult(false, null, "Non-2xx response: " + response.getStatusCode());
            }
        
        } catch (Exception e) {
            if (e instanceof org.springframework.web.client.HttpClientErrorException httpError) {
                if (httpError.getStatusCode() == HttpStatus.CONFLICT) {
                    var existingId = extractExistingId(httpError.getResponseBodyAsString());
                    if (existingId.isPresent()) {
                        log.info("Contact already exists in HubSpot with id={}, attempting update", existingId.get());
                        if (updateExistingContact(existingId.get(), lead)) {
                            return new HubspotSyncResult(true, existingId.get(), null);
                        }
                    }
                }
                log.error("Error syncing lead to Hubspot: status={} body={}", httpError.getStatusCode(), httpError.getResponseBodyAsString(), httpError);
                return new HubspotSyncResult(false, null, "HubSpot API error: " + httpError.getStatusCode());
            }
            log.error("Error syncing lead to Hubspot", e);
            return new HubspotSyncResult(false, null, e.getMessage());
        } 
    }

    private Optional<String> extractExistingId(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            return Optional.empty();
        }
        try {
            JsonNode node = OBJECT_MAPPER.readTree(responseBody);
            if (node.has("message")) {
                String message = node.get("message").asText("");
                int markerIndex = message.indexOf("Existing ID:");
                if (markerIndex >= 0) {
                    String id = message.substring(markerIndex + "Existing ID:".length()).trim();
                    if (!id.isEmpty()) {
                        return Optional.of(id);
                    }
                }
            }
        } catch (Exception ex) {
            log.debug("Unable to parse HubSpot conflict response", ex);
        }
        return Optional.empty();
    }

    private boolean updateExistingContact(String hubspotId, Lead lead) {
        try {
            RestTemplate restTemplate = new RestTemplate();
            String url = hubspotBaseUrl + "/crm/v3/objects/contacts/" + hubspotId;

            Map<String, Object> requestBody = Map.of(
                "properties", Map.of(
                    "firstname", lead.getName(),
                    "phone", lead.getPhone()
                )
            );

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(hubspotToken);

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);
            ResponseEntity<Map<String, Object>> response = restTemplate.exchange(url, HttpMethod.PATCH, entity, (Class<Map<String, Object>>) (Class<?>) Map.class);
            if (response.getStatusCode().is2xxSuccessful()) {
                log.info("Updated existing HubSpot contact {}", hubspotId);
                return true;
            }
            log.warn("Failed to update existing HubSpot contact {}. Status={}", hubspotId, response.getStatusCode());
        } catch (Exception ex) {
            log.error("Error updating existing HubSpot contact {}", hubspotId, ex);
        }
        return false;
    }

    // Simple result class for sync operation
    public static class HubspotSyncResult {
        private boolean success;
        private String hubspotId;
        private String error;

        public HubspotSyncResult(boolean success, String hubspotId, String error) {
            this.success = success;
            this.hubspotId = hubspotId;
            this.error = error;
        }

        // Patch: add isSuccess() and getId() for compatibility
        public boolean isSuccess() {
            return success;
        }
        public String getId() {
            return hubspotId;
        }
        public String getError() {
            return error;
        }

    }
}
