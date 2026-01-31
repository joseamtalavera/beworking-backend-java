package com.beworking.contacts;

import com.beworking.auth.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link ContactProfileService}.
 *
 * Tests the business logic layer independently of the database by mocking the repository.
 */
@ExtendWith(MockitoExtension.class)
public class ContactProfileServiceTest {

    @Mock
    private ContactProfileRepository repository;

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private ContactProfileService service;

    private ContactProfile validContact;

    @BeforeEach
    void setUp() {
        validContact = new ContactProfile();
        validContact.setId(1L);
        validContact.setName("Acme Corp");
        validContact.setEmailPrimary("acme@example.com");
        validContact.setContactName("John Doe");
        validContact.setPhonePrimary("+34600000000");
        validContact.setStatus("Activo");
        validContact.setTenantType("Usuario Virtual");
        validContact.setActive(true);
        validContact.setCreatedAt(LocalDateTime.now().minusDays(5));
    }

    // ==================== getContactProfiles ====================

    @Nested
    class GetContactProfiles {

        @Test
        void returnsPagedResults() {
            Page<ContactProfile> page = new PageImpl<>(List.of(validContact));
            when(repository.findAll(any(Specification.class), any(Pageable.class))).thenReturn(page);

            ContactProfilesPageResponse response = service.getContactProfiles(
                0, 10, null, null, null, null, null, null, null
            );

            assertThat(response.items()).hasSize(1);
            assertThat(response.items().get(0).name()).isEqualTo("Acme Corp");
            assertThat(response.totalElements()).isEqualTo(1);
        }

        @Test
        void emptyResults_returnsEmptyPage() {
            Page<ContactProfile> page = new PageImpl<>(List.of());
            when(repository.findAll(any(Specification.class), any(Pageable.class))).thenReturn(page);

            ContactProfilesPageResponse response = service.getContactProfiles(
                0, 10, null, null, null, null, null, null, null
            );

            assertThat(response.items()).isEmpty();
            assertThat(response.totalElements()).isEqualTo(0);
        }

        @Test
        void negativePage_clampedToZero() {
            Page<ContactProfile> page = new PageImpl<>(List.of());
            when(repository.findAll(any(Specification.class), any(Pageable.class))).thenReturn(page);

            service.getContactProfiles(-1, 10, null, null, null, null, null, null, null);

            ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
            verify(repository).findAll(any(Specification.class), captor.capture());
            assertThat(captor.getValue().getPageNumber()).isEqualTo(0);
        }

        @Test
        void zeroSize_clampedToOne() {
            Page<ContactProfile> page = new PageImpl<>(List.of());
            when(repository.findAll(any(Specification.class), any(Pageable.class))).thenReturn(page);

            service.getContactProfiles(0, 0, null, null, null, null, null, null, null);

            ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
            verify(repository).findAll(any(Specification.class), captor.capture());
            assertThat(captor.getValue().getPageSize()).isEqualTo(1);
        }
    }

    // ==================== Response mapping ====================

    @Nested
    class ResponseMapping {

        @Test
        void mapsContactNameFromContactNameField() {
            validContact.setContactName("Jane Smith");
            Page<ContactProfile> page = new PageImpl<>(List.of(validContact));
            when(repository.findAll(any(Specification.class), any(Pageable.class))).thenReturn(page);

            ContactProfilesPageResponse response = service.getContactProfiles(
                0, 10, null, null, null, null, null, null, null
            );

            assertThat(response.items().get(0).contact().name()).isEqualTo("Jane Smith");
        }

        @Test
        void fallsBackToRepresentativeNames() {
            validContact.setContactName(null);
            validContact.setRepresentativeFirstName("Maria");
            validContact.setRepresentativeLastName("Garcia");
            Page<ContactProfile> page = new PageImpl<>(List.of(validContact));
            when(repository.findAll(any(Specification.class), any(Pageable.class))).thenReturn(page);

            ContactProfilesPageResponse response = service.getContactProfiles(
                0, 10, null, null, null, null, null, null, null
            );

            assertThat(response.items().get(0).contact().name()).isEqualTo("Maria Garcia");
        }

        @Test
        void mapsEmailFromPrimaryEmail() {
            Page<ContactProfile> page = new PageImpl<>(List.of(validContact));
            when(repository.findAll(any(Specification.class), any(Pageable.class))).thenReturn(page);

            ContactProfilesPageResponse response = service.getContactProfiles(
                0, 10, null, null, null, null, null, null, null
            );

            assertThat(response.items().get(0).contact().email()).isEqualTo("acme@example.com");
        }

        @Test
        void fallsBackToSecondaryEmail() {
            validContact.setEmailPrimary(null);
            validContact.setEmailSecondary("billing@example.com");
            Page<ContactProfile> page = new PageImpl<>(List.of(validContact));
            when(repository.findAll(any(Specification.class), any(Pageable.class))).thenReturn(page);

            ContactProfilesPageResponse response = service.getContactProfiles(
                0, 10, null, null, null, null, null, null, null
            );

            assertThat(response.items().get(0).contact().email()).isEqualTo("billing@example.com");
        }

        @Test
        void resolvesStatusFromField() {
            validContact.setStatus("Potencial");
            Page<ContactProfile> page = new PageImpl<>(List.of(validContact));
            when(repository.findAll(any(Specification.class), any(Pageable.class))).thenReturn(page);

            ContactProfilesPageResponse response = service.getContactProfiles(
                0, 10, null, null, null, null, null, null, null
            );

            assertThat(response.items().get(0).status()).isEqualTo("Potencial");
        }

        @Test
        void resolvesStatusFromActiveFlag_whenStatusNull() {
            validContact.setStatus(null);
            validContact.setActive(true);
            Page<ContactProfile> page = new PageImpl<>(List.of(validContact));
            when(repository.findAll(any(Specification.class), any(Pageable.class))).thenReturn(page);

            ContactProfilesPageResponse response = service.getContactProfiles(
                0, 10, null, null, null, null, null, null, null
            );

            assertThat(response.items().get(0).status()).isEqualTo("Active");
        }

        @Test
        void resolvesStatusUnknown_whenBothNull() {
            validContact.setStatus(null);
            validContact.setActive(null);
            Page<ContactProfile> page = new PageImpl<>(List.of(validContact));
            when(repository.findAll(any(Specification.class), any(Pageable.class))).thenReturn(page);

            ContactProfilesPageResponse response = service.getContactProfiles(
                0, 10, null, null, null, null, null, null, null
            );

            assertThat(response.items().get(0).status()).isEqualTo("Unknown");
        }

        @Test
        void formatsLastActive_recentTimestamp() {
            validContact.setStatusChangedAt(LocalDateTime.now().minusMinutes(30));
            Page<ContactProfile> page = new PageImpl<>(List.of(validContact));
            when(repository.findAll(any(Specification.class), any(Pageable.class))).thenReturn(page);

            ContactProfilesPageResponse response = service.getContactProfiles(
                0, 10, null, null, null, null, null, null, null
            );

            assertThat(response.items().get(0).lastActive()).isEqualTo("30m ago");
        }

        @Test
        void mapsUserType_fromTenantType() {
            validContact.setTenantType("Distribuidor");
            Page<ContactProfile> page = new PageImpl<>(List.of(validContact));
            when(repository.findAll(any(Specification.class), any(Pageable.class))).thenReturn(page);

            ContactProfilesPageResponse response = service.getContactProfiles(
                0, 10, null, null, null, null, null, null, null
            );

            assertThat(response.items().get(0).userType()).isEqualTo("Distribuidor");
        }
    }

    // ==================== getContactProfileById ====================

    @Nested
    class GetContactProfileById {

        @Test
        void existingId_returnsProfile() {
            when(repository.findById(1L)).thenReturn(Optional.of(validContact));

            ContactProfile result = service.getContactProfileById(1L);

            assertThat(result.getName()).isEqualTo("Acme Corp");
        }

        @Test
        void nonExistingId_throwsNotFound() {
            when(repository.findById(999L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getContactProfileById(999L))
                .isInstanceOf(ContactProfileService.ContactProfileNotFoundException.class)
                .hasMessageContaining("999");
        }
    }

    // ==================== createContactProfile ====================

    @Nested
    class CreateContactProfile {

        @Test
        void setsFieldsFromRequest() {
            ContactProfileRequest request = new ContactProfileRequest("New Corp", "new@example.com");
            request.setStatus("Potencial");
            request.setUserType("Usuario Virtual");
            request.setPhone("+34600111222");

            when(repository.save(any(ContactProfile.class))).thenAnswer(inv -> inv.getArgument(0));

            ContactProfile result = service.createContactProfile(request);

            assertThat(result.getName()).isEqualTo("New Corp");
            assertThat(result.getEmailPrimary()).isEqualTo("new@example.com");
            assertThat(result.getStatus()).isEqualTo("Potencial");
            assertThat(result.getTenantType()).isEqualTo("Usuario Virtual");
            assertThat(result.getPhonePrimary()).isEqualTo("+34600111222");
            assertThat(result.getActive()).isTrue();
            assertThat(result.getCreatedAt()).isNotNull();
        }

        @Test
        void defaultStatus_isPotencial() {
            ContactProfileRequest request = new ContactProfileRequest("Corp", "corp@example.com");
            // No status set

            when(repository.save(any(ContactProfile.class))).thenAnswer(inv -> inv.getArgument(0));

            ContactProfile result = service.createContactProfile(request);

            assertThat(result.getStatus()).isEqualTo("Potencial");
        }

        @Test
        void syncsAvatarToUserTable() {
            ContactProfileRequest request = new ContactProfileRequest("Corp", "user@example.com");
            request.setAvatar("https://example.com/avatar.png");

            com.beworking.auth.User user = new com.beworking.auth.User();
            user.setEmail("user@example.com");

            when(repository.save(any(ContactProfile.class))).thenAnswer(inv -> inv.getArgument(0));
            when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(user));

            service.createContactProfile(request);

            verify(userRepository).findByEmail("user@example.com");
            verify(userRepository).save(user);
            assertThat(user.getAvatar()).isEqualTo("https://example.com/avatar.png");
        }
    }

    // ==================== updateContactProfile ====================

    @Nested
    class UpdateContactProfile {

        @Test
        void updatesNameAndEmail() {
            when(repository.findById(1L)).thenReturn(Optional.of(validContact));
            when(repository.save(any(ContactProfile.class))).thenAnswer(inv -> inv.getArgument(0));

            ContactProfileRequest request = new ContactProfileRequest();
            request.setName("Updated Name");
            request.setEmail("updated@example.com");

            ContactProfile result = service.updateContactProfile(1L, request);

            assertThat(result.getName()).isEqualTo("Updated Name");
            assertThat(result.getEmailPrimary()).isEqualTo("updated@example.com");
        }

        @Test
        void updatesStatus_andTimestamp() {
            validContact.setStatus("Potencial");
            LocalDateTime originalTimestamp = validContact.getStatusChangedAt();

            when(repository.findById(1L)).thenReturn(Optional.of(validContact));
            when(repository.save(any(ContactProfile.class))).thenAnswer(inv -> inv.getArgument(0));

            ContactProfileRequest request = new ContactProfileRequest();
            request.setStatus("Activo");

            ContactProfile result = service.updateContactProfile(1L, request);

            assertThat(result.getStatus()).isEqualTo("Activo");
            assertThat(result.getStatusChangedAt()).isNotEqualTo(originalTimestamp);
        }

        @Test
        void skipsBlankName() {
            when(repository.findById(1L)).thenReturn(Optional.of(validContact));
            when(repository.save(any(ContactProfile.class))).thenAnswer(inv -> inv.getArgument(0));

            ContactProfileRequest request = new ContactProfileRequest();
            request.setName("   ");

            ContactProfile result = service.updateContactProfile(1L, request);

            assertThat(result.getName()).isEqualTo("Acme Corp"); // unchanged
        }

        @Test
        void updatesBillingFields() {
            when(repository.findById(1L)).thenReturn(Optional.of(validContact));
            when(repository.save(any(ContactProfile.class))).thenAnswer(inv -> inv.getArgument(0));

            ContactProfileRequest request = new ContactProfileRequest();
            request.setBillingCompany("Billing Corp");
            request.setBillingAddress("123 Main St");
            request.setBillingPostalCode("28001");
            request.setBillingCountry("Spain");

            ContactProfile result = service.updateContactProfile(1L, request);

            assertThat(result.getBillingName()).isEqualTo("Billing Corp");
            assertThat(result.getBillingAddress()).isEqualTo("123 Main St");
            assertThat(result.getBillingPostalCode()).isEqualTo("28001");
            assertThat(result.getBillingCountry()).isEqualTo("Spain");
        }

        @Test
        void nonExistingId_throwsNotFound() {
            when(repository.findById(999L)).thenReturn(Optional.empty());

            ContactProfileRequest request = new ContactProfileRequest();
            request.setName("Ghost");

            assertThatThrownBy(() -> service.updateContactProfile(999L, request))
                .isInstanceOf(ContactProfileService.ContactProfileNotFoundException.class);
        }
    }

    // ==================== deleteContactProfile ====================

    @Nested
    class DeleteContactProfile {

        @Test
        void existingProfile_deletesAndReturnsTrue() {
            when(repository.existsById(1L)).thenReturn(true);

            boolean result = service.deleteContactProfile(1L);

            assertThat(result).isTrue();
            verify(repository).deleteById(1L);
        }

        @Test
        void nonExistingProfile_returnsFalse() {
            when(repository.existsById(999L)).thenReturn(false);

            boolean result = service.deleteContactProfile(999L);

            assertThat(result).isFalse();
            verify(repository, never()).deleteById(anyLong());
        }
    }

    // ==================== findContactByEmail ====================

    @Nested
    class FindContactByEmail {

        @Test
        void nullEmail_returnsEmpty() {
            Optional<ContactProfile> result = service.findContactByEmail(null);
            assertThat(result).isEmpty();
        }

        @Test
        void blankEmail_returnsEmpty() {
            Optional<ContactProfile> result = service.findContactByEmail("   ");
            assertThat(result).isEmpty();
        }

        @Test
        void validEmail_delegatesToRepository() {
            when(repository.findFirstByEmailPrimaryIgnoreCaseOrEmailSecondaryIgnoreCaseOrEmailTertiaryIgnoreCaseOrRepresentativeEmailIgnoreCase(
                any(), any(), any(), any()
            )).thenReturn(Optional.of(validContact));

            Optional<ContactProfile> result = service.findContactByEmail("acme@example.com");

            assertThat(result).isPresent();
            assertThat(result.get().getName()).isEqualTo("Acme Corp");
        }
    }
}
