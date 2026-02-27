package com.beworking.contacts;

import com.beworking.auth.JwtUtil;
import com.beworking.auth.User;
import com.beworking.auth.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Controller slice tests for {@link ContactProfileController}.
 *
 * Uses @WebMvcTest to test the HTTP layer only. Security filters are disabled
 * and dependencies are mocked so tests run fast and deterministically.
 */
@WebMvcTest(ContactProfileController.class)
@AutoConfigureMockMvc(addFilters = false)
public class ContactProfileControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private ContactProfileService contactProfileService;

    @MockBean
    private UserRepository userRepository;

    @MockBean
    private JwtUtil jwtUtil;

    @MockBean
    private ViesVatService viesVatService;

    @MockBean
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    // --- Helper to build a mock admin user ---
    private User adminUser() {
        User user = new User();
        user.setEmail("admin@beworking.com");
        user.setRole(User.Role.ADMIN);
        return user;
    }

    private User regularUser(Long tenantId) {
        User user = new User();
        user.setEmail("user@beworking.com");
        user.setRole(User.Role.USER);
        user.setTenantId(tenantId);
        return user;
    }

    private Authentication adminAuthentication() {
        TestingAuthenticationToken token = new TestingAuthenticationToken("admin@beworking.com", "password");
        token.setAuthenticated(true);
        return token;
    }

    private Authentication userAuthentication() {
        TestingAuthenticationToken token = new TestingAuthenticationToken("user@beworking.com", "password");
        token.setAuthenticated(true);
        return token;
    }

    private ContactProfilesPageResponse emptyPage() {
        return new ContactProfilesPageResponse(List.of(), 0, 10, 0, 0, false, false);
    }

    private ContactProfilesPageResponse pageWith(ContactProfileResponse... items) {
        return new ContactProfilesPageResponse(
            List.of(items), 0, 10, items.length, 1, false, false
        );
    }

    private ContactProfileResponse sampleResponse(Long id, String name, String email) {
        return new ContactProfileResponse(
            id, name,
            new ContactProfileResponse.Contact(name, email),
            "Virtual", null, "Usuario Virtual", "Activo",
            0, 0.0, null, null, null, null, null,
            new ContactProfileResponse.Billing(name, email, null, null, null, null, null, null, null)
        );
    }

    // ==================== GET /api/contact-profiles ====================

    @Test
    void getContacts_asAdmin_returnsAllContacts() throws Exception {
        when(userRepository.findByEmail("admin@beworking.com")).thenReturn(Optional.of(adminUser()));
        when(contactProfileService.getContactProfiles(
            anyInt(), anyInt(), any(), any(), any(), any(), any(), any(), any()
        )).thenReturn(pageWith(
            sampleResponse(1L, "Acme Corp", "acme@example.com"),
            sampleResponse(2L, "Beta Inc", "beta@example.com")
        ));

        mockMvc.perform(get("/api/contact-profiles")
                .principal(adminAuthentication()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalElements").value(2))
            .andExpect(jsonPath("$.items[0].name").value("Acme Corp"))
            .andExpect(jsonPath("$.items[1].name").value("Beta Inc"));
    }

    @Test
    void getContacts_asUser_returnsOnlyOwnContact() throws Exception {
        when(userRepository.findByEmail("user@beworking.com")).thenReturn(Optional.of(regularUser(100L)));
        when(contactProfileService.getContactProfilesByTenantId(
            eq(100L), anyInt(), anyInt(), any(), any(), any(), any(), any(), any(), any()
        )).thenReturn(pageWith(
            sampleResponse(100L, "My Company", "user@beworking.com")
        ));

        mockMvc.perform(get("/api/contact-profiles")
                .principal(userAuthentication()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalElements").value(1))
            .andExpect(jsonPath("$.items[0].id").value(100));

        verify(contactProfileService, never()).getContactProfiles(
            anyInt(), anyInt(), any(), any(), any(), any(), any(), any(), any()
        );
    }

    @Test
    void getContacts_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/api/contact-profiles"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void getContacts_withSearchParam_passesSearchToService() throws Exception {
        when(userRepository.findByEmail("admin@beworking.com")).thenReturn(Optional.of(adminUser()));
        when(contactProfileService.getContactProfiles(
            anyInt(), anyInt(), eq("acme"), any(), any(), any(), any(), any(), any()
        )).thenReturn(pageWith(sampleResponse(1L, "Acme Corp", "acme@example.com")));

        mockMvc.perform(get("/api/contact-profiles")
                .param("search", "acme")
                .principal(adminAuthentication()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items[0].name").value("Acme Corp"));

        verify(contactProfileService).getContactProfiles(
            eq(0), eq(25), eq("acme"), isNull(), isNull(), isNull(), isNull(), isNull(), isNull()
        );
    }

    @Test
    void getContacts_withStatusFilter_passesStatusToService() throws Exception {
        when(userRepository.findByEmail("admin@beworking.com")).thenReturn(Optional.of(adminUser()));
        when(contactProfileService.getContactProfiles(
            anyInt(), anyInt(), any(), eq("Activo"), any(), any(), any(), any(), any()
        )).thenReturn(emptyPage());

        mockMvc.perform(get("/api/contact-profiles")
                .param("status", "Activo")
                .principal(adminAuthentication()))
            .andExpect(status().isOk());

        verify(contactProfileService).getContactProfiles(
            eq(0), eq(25), isNull(), eq("Activo"), isNull(), isNull(), isNull(), isNull(), isNull()
        );
    }

    @Test
    void getContacts_withPagination_passesPageAndSize() throws Exception {
        when(userRepository.findByEmail("admin@beworking.com")).thenReturn(Optional.of(adminUser()));
        when(contactProfileService.getContactProfiles(
            eq(2), eq(10), any(), any(), any(), any(), any(), any(), any()
        )).thenReturn(emptyPage());

        mockMvc.perform(get("/api/contact-profiles")
                .param("page", "2")
                .param("size", "10")
                .principal(adminAuthentication()))
            .andExpect(status().isOk());

        verify(contactProfileService).getContactProfiles(
            eq(2), eq(10), isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), isNull()
        );
    }

    // ==================== GET /api/contact-profiles/{id} ====================

    @Test
    void getContactById_asAdmin_returnsContact() throws Exception {
        ContactProfile profile = new ContactProfile();
        profile.setId(1L);
        profile.setName("Acme Corp");
        profile.setEmailPrimary("acme@example.com");

        when(userRepository.findByEmail("admin@beworking.com")).thenReturn(Optional.of(adminUser()));
        when(contactProfileService.getContactProfileById(1L)).thenReturn(profile);

        mockMvc.perform(get("/api/contact-profiles/1")
                .principal(adminAuthentication()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value("Acme Corp"));
    }

    @Test
    void getContactById_asUser_forbiddenForOtherContact() throws Exception {
        ContactProfile profile = new ContactProfile();
        profile.setId(999L);
        profile.setName("Other Company");

        when(userRepository.findByEmail("user@beworking.com")).thenReturn(Optional.of(regularUser(100L)));
        when(contactProfileService.getContactProfileById(999L)).thenReturn(profile);

        mockMvc.perform(get("/api/contact-profiles/999")
                .principal(userAuthentication()))
            .andExpect(status().isForbidden());
    }

    @Test
    void getContactById_notFound_returns404() throws Exception {
        when(userRepository.findByEmail("admin@beworking.com")).thenReturn(Optional.of(adminUser()));
        when(contactProfileService.getContactProfileById(999L))
            .thenThrow(new ContactProfileService.ContactProfileNotFoundException(999L));

        mockMvc.perform(get("/api/contact-profiles/999")
                .principal(adminAuthentication()))
            .andExpect(status().isNotFound());
    }

    // ==================== POST /api/contact-profiles ====================

    @Test
    void createContact_validRequest_returns201() throws Exception {
        ContactProfileRequest request = new ContactProfileRequest("New Corp", "new@example.com");
        request.setStatus("Potencial");
        request.setUserType("Usuario Virtual");

        ContactProfile created = new ContactProfile();
        created.setId(42L);
        created.setName("New Corp");
        created.setEmailPrimary("new@example.com");

        when(contactProfileService.createContactProfile(any(ContactProfileRequest.class))).thenReturn(created);

        mockMvc.perform(post("/api/contact-profiles")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.id").value(42))
            .andExpect(jsonPath("$.name").value("New Corp"));
    }

    // ==================== PUT /api/contact-profiles/{id} ====================

    @Test
    void updateContact_validRequest_returnsUpdated() throws Exception {
        ContactProfileRequest request = new ContactProfileRequest();
        request.setName("Updated Corp");
        request.setEmail("updated@example.com");

        ContactProfile updated = new ContactProfile();
        updated.setId(1L);
        updated.setName("Updated Corp");
        updated.setEmailPrimary("updated@example.com");

        when(contactProfileService.updateContactProfile(eq(1L), any(ContactProfileRequest.class))).thenReturn(updated);

        mockMvc.perform(put("/api/contact-profiles/1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value("Updated Corp"));
    }

    @Test
    void updateContact_notFound_returns404() throws Exception {
        ContactProfileRequest request = new ContactProfileRequest();
        request.setName("Ghost");

        when(contactProfileService.updateContactProfile(eq(999L), any(ContactProfileRequest.class)))
            .thenThrow(new ContactProfileService.ContactProfileNotFoundException(999L));

        mockMvc.perform(put("/api/contact-profiles/999")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isNotFound());
    }

    // ==================== DELETE /api/contact-profiles/{id} ====================

    @Test
    void deleteContact_exists_returns204() throws Exception {
        when(contactProfileService.deleteContactProfile(1L)).thenReturn(true);

        mockMvc.perform(delete("/api/contact-profiles/1"))
            .andExpect(status().isNoContent());
    }

    @Test
    void deleteContact_notFound_returns404() throws Exception {
        when(contactProfileService.deleteContactProfile(999L)).thenReturn(false);

        mockMvc.perform(delete("/api/contact-profiles/999"))
            .andExpect(status().isNotFound());
    }
}
