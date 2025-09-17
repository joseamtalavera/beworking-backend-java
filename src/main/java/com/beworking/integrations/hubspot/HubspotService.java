
package com.beworking.integrations.hubspot; // Import for Hubspot integration service

import com.beworking.leads.Lead; 
import com.beworking.leads.LeadRepository;
import org.slf4j.Logger; 
import org.slf4j.LoggerFactory; 
import org.springframework.beans.factory.annotation.Value; // Import for Value annotation to inject properties from application.properties
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.http.*;
import java.util.Map;
import java.util.HashMap;
@Service
public class HubspotService {
    private static final Logger log = LoggerFactory.getLogger(HubspotService.class);

    private final LeadRepository leadRepository;

    @Value("${hubspot.api.baseUrl:https://api.hubapi.com}")
    private String hubspotBaseUrl;
    @Value ("${beworking.api.token:}")
    private String hubspotToken;

    public HubspotService(LeadRepository leadRepository) {
        this.leadRepository = leadRepository;
    }
    
    //Sync a lead with HubSpot. Return a HubspotSyncResult with status, id, and error message if any.

    public HubspotSyncResult syncLead(Lead lead) {
        log.info("HubspotService.synclead stub called for lead id={}", lead.getId());
        try {
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
            log.error("Error syncing lead to Hubspot", e);
            return new HubspotSyncResult(false, null, e.getMessage());
        } 
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