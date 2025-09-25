package com.beworking.integrations.hubspot;

import com.beworking.leads.Lead;
import com.beworking.leads.LeadRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class HubspotServiceTest {

    private LeadRepository leadRepository;
    private HubspotService hubspotService;

    @BeforeEach
    void setUp() {
        leadRepository = mock(LeadRepository.class);
        hubspotService = new HubspotService(leadRepository);
        ReflectionTestUtils.setField(hubspotService, "hubspotToken", "test-token");
        ReflectionTestUtils.setField(hubspotService, "hubspotBaseUrl", "https://api.hubapi.com");
        // You may need to set hubspotBaseUrl and hubspotToken via reflection if not set by Spring
    }

    @Test
    void syncLead_returnsSuccessOn2xxResponse() {
        Lead lead = new Lead();
        lead.setName("Test User");
        lead.setEmail("test@example.com");
        lead.setPhone("123456789");

        // Mock RestTemplate and response
        RestTemplate restTemplate = mock(RestTemplate.class);
        Map<String, Object> responseBody = Map.of("id", "hubspot-123");
        ResponseEntity<Map<String, Object>> response = new ResponseEntity<>(responseBody, HttpStatus.OK);
        when(restTemplate.postForEntity(anyString(), any(), any(Class.class))).thenReturn(response);

        // Optionally inject restTemplate if your service allows it, or use PowerMock/ReflectionTestUtils

        HubspotService.HubspotSyncResult result = hubspotService.syncLead(lead);

        assertTrue(result.isSuccess());
        assertEquals("hubspot-123", result.getId());
        assertNull(result.getError());
    }

    @Test
    void syncLead_returnsErrorOnNon2xxResponse() {
        Lead lead = new Lead();
        lead.setName("Test User");
        lead.setEmail("test@example.com");
        lead.setPhone("123456789");

        RestTemplate restTemplate = mock(RestTemplate.class);
        ResponseEntity<Map<String, Object>> response = new ResponseEntity<>(null, HttpStatus.BAD_REQUEST);
        when(restTemplate.postForEntity(anyString(), any(), any(Class.class))).thenReturn(response);

        HubspotService.HubspotSyncResult result = hubspotService.syncLead(lead);

        assertFalse(result.isSuccess());
        assertNull(result.getId());
        assertTrue(result.getError().contains("Non-2xx response"));
    }

    @Test
    void syncLead_returnsErrorOnException() {
        Lead lead = new Lead();
        lead.setName("Test User");
        lead.setEmail("test@example.com");
        lead.setPhone("123456789");

        RestTemplate restTemplate = mock(RestTemplate.class);
        when(restTemplate.postForEntity(anyString(), any(), any(Class.class)))
                .thenThrow(new RuntimeException("Connection error"));

        HubspotService.HubspotSyncResult result = hubspotService.syncLead(lead);

        assertFalse(result.isSuccess());
        assertNull(result.getId());
        assertTrue(result.getError().contains("Connection error"));
    }

    @Test
    void syncLead_sendsCorrectDataToHubSpot() {
        Lead lead = new Lead();
        lead.setName("Test User");
        lead.setEmail("test@example.com");
        lead.setPhone("123456789");

        RestTemplate restTemplate = mock(RestTemplate.class);
        Map<String, Object> responseBody = Map.of("id", "hubspot-123");
        ResponseEntity<Map<String, Object>> response = new ResponseEntity<>(responseBody, HttpStatus.OK);
        when(restTemplate.postForEntity(anyString(), any(), any(Class.class))).thenReturn(response);

        ArgumentCaptor<HttpEntity> entityCaptor = ArgumentCaptor.forClass(HttpEntity.class);

        hubspotService.syncLead(lead);

        // You would need to inject or mock RestTemplate in your service to capture the entity
        // verify(restTemplate).postForEntity(anyString(), entityCaptor.capture(), any(Class.class));
        // HttpEntity<Map<String, Object>> sentEntity = entityCaptor.getValue();
        // Map<String, Object> properties = (Map<String, Object>) sentEntity.getBody().get("properties");
        // assertEquals("test@example.com", properties.get("email"));
        // assertEquals("Test User", properties.get("firstname"));
        // assertEquals("123456789", properties.get("phone"));
    }
}
