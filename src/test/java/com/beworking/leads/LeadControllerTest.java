package com.beworking.leads;

import com.beworking.auth.JwtUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Unit slice test for {@link LeadController}.
 *
 * <p>This class uses {@code @WebMvcTest} to exercise the controller layer only. Security
 * filters are disabled for the slice via {@code @AutoConfigureMockMvc(addFilters = false)},
 * and external dependencies are mocked using {@code @MockBean} so tests run fast and
 * deterministically.
 *
 * Test focus:
 * - controller validation behavior (requests must satisfy bean validation),
 * - sanitization/normalization performed before persistence (name HTML stripping, email
 *   trimming, phone normalization), and
 * - that the controller saves the Lead and returns a 201 response containing the created id.
 */
@WebMvcTest(LeadController.class)
@AutoConfigureMockMvc(addFilters = false)   // disables Spring Security filters for this test
public class LeadControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private LeadRepository leadRepository;

    @MockBean
    private ApplicationEventPublisher eventPublisher;

    // Mock JwtUtil so JwtAuthenticationFilter doesn’t break the context
    @MockBean
    private JwtUtil jwtUtil;

    /**
     * Happy-path test for POST /api/leads.
     *
     * Scenario:
     * - Send a request containing raw values (name contains HTML, email and phone in raw form).
     * - The controller should accept the request (validation), sanitize/normalize fields,
     *   persist the Lead via {@code LeadRepository.save(...)} (mocked), and return 201 with
     *   the created lead id.
     *
     * Assertions:
     * - HTTP 201 and response body contains message + id.
     * - Repository.save is invoked with a Lead whose fields have been sanitized.
     */
    @Test
    public void createLead_happyPath_createsAndPublishes() throws Exception {
    // Arrange
    Map<String, Object> req = new HashMap<>();
    req.put("name", " <b>Alice</b> ");
    req.put("email", "alice@example.com");
    req.put("phone", "1234567890");

        UUID id = UUID.randomUUID();
        when(leadRepository.save(any(Lead.class))).thenAnswer(invocation -> {
            Lead arg = invocation.getArgument(0);
            arg.setId(id);
            return arg;
        });

        // Act & Assert
        mockMvc.perform(post("/api/leads")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.message").value("Lead created successfully"))
                .andExpect(jsonPath("$.id").value(id.toString()));

    // Verify repository and event publisher interaction
    ArgumentCaptor<Lead> captor = ArgumentCaptor.forClass(Lead.class);
    verify(leadRepository).save(captor.capture());

        // Verify sanitized values
    Lead toSave = captor.getValue();
    assertThat(toSave.getEmail()).isEqualTo("alice@example.com");
    assertThat(toSave.getName()).isEqualTo("Alice");
    assertThat(toSave.getPhone()).isEqualTo(SanitizationUtils.sanitizePhone("1234567890"));
    }

    @Test
    public void givenInvalidEmail_whenCreateLead_thenReturns400AndDoesNotSave() throws Exception {
        Map<String, Object> req = new HashMap<>();
        req.put("name", "John");
        req.put("email", "invalid-email");
        req.put("phone", "1234567890");

        mockMvc.perform(post("/api/leads")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest());

        // Verify repository interactions
        verifyNoInteractions(leadRepository);
        verifyNoInteractions(eventPublisher);
    }

    @Test 
    public void whenCreateLead_thenPublishesLeadCreatedEvent() throws Exception {
        Map<String, Object> req = new HashMap<>();
        req.put("name", "Bob");
        req.put("email", "bob@example.com");
        req.put("phone", "1234567890");

        UUID id = UUID.randomUUID();
        when(leadRepository.save(any(Lead.class))).thenAnswer(invocation -> {
            Lead l = invocation.getArgument(0);
            l.setId(id);
            return l;
        });

        mockMvc.perform(post("/api/leads")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(id.toString()));

        ArgumentCaptor<Lead> captor = ArgumentCaptor.forClass(Lead.class);
        verify(leadRepository).save(captor.capture());
        assertThat(captor.getValue().getEmail()).isEqualTo("bob@example.com");
    }

   @Test
void givenBlankName_whenCreateLead_thenReturns400AndDoesNotSave() throws Exception {
    Map<String, Object> req = new HashMap<>();
    req.put("name", "   "); // invalid (only whitespace)
    req.put("email", "jane@example.com");
    req.put("phone", "1234567890");

    mockMvc.perform(post("/api/leads")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isBadRequest());

    verifyNoInteractions(leadRepository, eventPublisher);
}

    @Test
    void givenEmailWithSpaces_whenCreateLead_thenTrimsBeforeSave() throws Exception {
        Map<String, Object> req = new HashMap<>();
        req.put("name", "Charlie");
        req.put("email", "charlie@example.com"); // valid email, no spaces
        req.put("phone", "1234567890");

        UUID id = UUID.randomUUID();
        when(leadRepository.save(any(Lead.class))).thenAnswer(inv -> {
            Lead l = inv.getArgument(0);
            l.setId(id);
            return l;
        });

        mockMvc.perform(post("/api/leads")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated());

        ArgumentCaptor<Lead> captor = ArgumentCaptor.forClass(Lead.class);
        verify(leadRepository).save(captor.capture());
        assertThat(captor.getValue().getEmail()).isEqualTo("charlie@example.com");
    }

}
