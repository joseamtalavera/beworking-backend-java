package com.beworking.contacts;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import com.beworking.auth.UserRepository;

@Service
public class ContactProfileService {

    public static class ContactProfileNotFoundException extends RuntimeException {
        public ContactProfileNotFoundException(Long id) {
            super("Contact profile not found: " + id);
        }
    }

    private static final DateTimeFormatter CREATED_AT_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    @PersistenceContext
    private EntityManager entityManager;
    private static final DateTimeFormatter LAST_ACTIVE_DATE_FORMATTER = DateTimeFormatter.ofPattern("MMM dd yyyy");

    private final ContactProfileRepository repository;
    private final UserRepository userRepository;

    public ContactProfileService(ContactProfileRepository repository, UserRepository userRepository) {
        this.repository = repository;
        this.userRepository = userRepository;
    }

    @Transactional(readOnly = true)
    public ContactProfilesPageResponse getContactProfiles(int page, int size, String search, String status, String plan, String tenantType, String email, String startDate, String endDate) {
        int pageIndex = Math.max(page, 0);
        int pageSize = Math.max(1, size);

        Specification<ContactProfile> specification = buildSpecification(search, status, plan, tenantType, email, startDate, endDate);
        Page<ContactProfile> profiles = repository.findAll(
            specification,
            PageRequest.of(pageIndex, pageSize, Sort.by(Sort.Direction.DESC, "createdAt"))
        );

        List<ContactProfileResponse> responses = new ArrayList<>(profiles.getNumberOfElements());
        for (ContactProfile profile : profiles.getContent()) {
            responses.add(mapToResponse(profile));
        }

        return new ContactProfilesPageResponse(
            responses,
            profiles.getNumber(),
            profiles.getSize(),
            profiles.getTotalElements(),
            profiles.getTotalPages(),
            profiles.hasNext(),
            profiles.hasPrevious()
        );
    }

    @Transactional(readOnly = true)
    public ContactProfilesPageResponse getContactProfilesByTenantId(Long tenantId, int page, int size, String search, String status, String plan, String tenantType, String email, String startDate, String endDate) {
        System.out.println("getContactProfilesByTenantId called with tenantId: " + tenantId);
        int pageIndex = Math.max(page, 0);
        int pageSize = Math.max(1, size);

        Specification<ContactProfile> specification = buildSpecification(search, status, plan, tenantType, email, startDate, endDate);
        // Add tenantId filter - the user's tenantId should match the contact's id
        specification = specification.and((root, query, criteriaBuilder) -> 
            criteriaBuilder.equal(root.get("id"), tenantId)
        );
        
        Page<ContactProfile> profiles = repository.findAll(
            specification,
            PageRequest.of(pageIndex, pageSize, Sort.by(Sort.Direction.DESC, "createdAt"))
        );

        List<ContactProfileResponse> responses = new ArrayList<>(profiles.getNumberOfElements());
        for (ContactProfile profile : profiles.getContent()) {
            responses.add(mapToResponse(profile));
        }

        return new ContactProfilesPageResponse(
            responses,
            profiles.getNumber(),
            profiles.getSize(),
            profiles.getTotalElements(),
            profiles.getTotalPages(),
            profiles.hasNext(),
            profiles.hasPrevious()
        );
    }

    @Transactional(readOnly = true)
    public Optional<ContactProfile> findContactByEmail(String userEmail) {
        if (userEmail == null || userEmail.isBlank()) {
            return Optional.empty();
        }
        String normalizedEmail = userEmail.trim().toLowerCase();
        return repository.findFirstByEmailPrimaryIgnoreCaseOrEmailSecondaryIgnoreCaseOrEmailTertiaryIgnoreCaseOrRepresentativeEmailIgnoreCase(
            normalizedEmail, normalizedEmail, normalizedEmail, normalizedEmail
        );

    }

    public ContactProfilesPageResponse getContactProfilesByEmail(String userEmail, int page, int size, String search, String status, String plan, String tenantType, String email, String startDate, String endDate) {
        int pageIndex = Math.max(page, 0);
        int pageSize = Math.max(1, size);

        Specification<ContactProfile> specification = buildSpecification(search, status, plan, tenantType, email, startDate, endDate);
        // Add email filter - find contact by user's email
        specification = specification.and((root, query, criteriaBuilder) -> 
            criteriaBuilder.equal(root.get("emailPrimary"), userEmail)
        );
        
        Page<ContactProfile> profiles = repository.findAll(
            specification,
            PageRequest.of(pageIndex, pageSize, Sort.by(Sort.Direction.DESC, "createdAt"))
        );

        List<ContactProfileResponse> responses = new ArrayList<>(profiles.getNumberOfElements());
        for (ContactProfile profile : profiles.getContent()) {
            responses.add(mapToResponse(profile));
        }

        return new ContactProfilesPageResponse(
            responses,
            profiles.getNumber(),
            profiles.getSize(),
            profiles.getTotalElements(),
            profiles.getTotalPages(),
            profiles.hasNext(),
            profiles.hasPrevious()
        );
    }

    private Specification<ContactProfile> buildSpecification(String search, String status, String plan, String tenantType, String email, String startDate, String endDate) {
        // Baseline: exclude contacts that have no real name or no email at all
        Specification<ContactProfile> specification = Specification.where((root, query, cb) -> {
            // Name must be non-null, non-blank, and not an auto-generated placeholder like "Cliente 12345"
            var hasRealName = cb.and(
                cb.isNotNull(root.get("name")),
                cb.notEqual(cb.trim(root.get("name")), ""),
                cb.not(cb.like(root.get("name"), "Cliente %"))
            );
            // At least one email field must be populated
            var hasEmail = cb.or(
                cb.and(cb.isNotNull(root.get("emailPrimary")), cb.notEqual(cb.trim(root.get("emailPrimary")), "")),
                cb.and(cb.isNotNull(root.get("emailSecondary")), cb.notEqual(cb.trim(root.get("emailSecondary")), "")),
                cb.and(cb.isNotNull(root.get("emailTertiary")), cb.notEqual(cb.trim(root.get("emailTertiary")), "")),
                cb.and(cb.isNotNull(root.get("representativeEmail")), cb.notEqual(cb.trim(root.get("representativeEmail")), ""))
            );
            return cb.and(hasRealName, hasEmail);
        });

        if (search != null && !search.isBlank()) {
            String likePattern = "%" + search.trim().toLowerCase() + "%";
            specification = specification.and((root, query, criteriaBuilder) -> criteriaBuilder.or(
                criteriaBuilder.like(criteriaBuilder.lower(root.get("name")), likePattern),
                criteriaBuilder.like(criteriaBuilder.lower(root.get("contactName")), likePattern),
                criteriaBuilder.like(criteriaBuilder.lower(root.get("billingName")), likePattern),
                criteriaBuilder.like(criteriaBuilder.lower(root.get("emailPrimary")), likePattern),
                criteriaBuilder.like(criteriaBuilder.lower(root.get("emailSecondary")), likePattern),
                criteriaBuilder.like(criteriaBuilder.lower(root.get("emailTertiary")), likePattern)
            ));
        }

        if (status != null && !status.isBlank()) {
            String normalizedStatus = status.trim().toLowerCase();
            specification = specification.and((root, query, criteriaBuilder) -> {
                if ("active".equals(normalizedStatus)) {
                    return criteriaBuilder.or(
                        criteriaBuilder.equal(criteriaBuilder.lower(root.get("status")), normalizedStatus),
                        criteriaBuilder.isTrue(root.get("active"))
                    );
                }
                if ("inactive".equals(normalizedStatus)) {
                    return criteriaBuilder.or(
                        criteriaBuilder.equal(criteriaBuilder.lower(root.get("status")), normalizedStatus),
                        criteriaBuilder.isFalse(root.get("active"))
                    );
                }
                return criteriaBuilder.equal(criteriaBuilder.lower(root.get("status")), normalizedStatus);
            });
        }

        if (plan != null && !plan.isBlank()) {
            String normalizedPlan = plan.trim().toLowerCase();
            specification = specification.and((root, query, criteriaBuilder) -> criteriaBuilder.or(
                criteriaBuilder.equal(criteriaBuilder.lower(root.get("assignment")), normalizedPlan),
                criteriaBuilder.equal(criteriaBuilder.lower(root.get("category")), normalizedPlan),
                criteriaBuilder.equal(criteriaBuilder.lower(root.get("tenantType")), normalizedPlan)
            ));
        }

        if (tenantType != null && !tenantType.isBlank()) {
            String normalizedTenantType = tenantType.trim();
            specification = specification.and((root, query, criteriaBuilder) -> 
                criteriaBuilder.equal(root.get("tenantType"), normalizedTenantType)
            );
        }

        if (email != null && !email.isBlank()) {
            String normalizedEmail = email.trim().toLowerCase();
            specification = specification.and((root, query, criteriaBuilder) ->
                criteriaBuilder.or(
                    criteriaBuilder.like(criteriaBuilder.lower(root.get("emailPrimary")), "%" + normalizedEmail + "%"),
                    criteriaBuilder.like(criteriaBuilder.lower(root.get("emailSecondary")), "%" + normalizedEmail + "%"),
                    criteriaBuilder.like(criteriaBuilder.lower(root.get("emailTertiary")), "%" + normalizedEmail + "%"),
                    criteriaBuilder.like(criteriaBuilder.lower(root.get("representativeEmail")), "%" + normalizedEmail + "%")
                )
            );
        }

        if (startDate != null && !startDate.isBlank()) {
            try {
                LocalDateTime start = java.time.LocalDate.parse(startDate.trim()).atStartOfDay();
                specification = specification.and((root, query, cb) ->
                    cb.greaterThanOrEqualTo(root.get("createdAt"), start)
                );
            } catch (Exception ignored) {
                // Invalid date format, skip filter
            }
        }

        if (endDate != null && !endDate.isBlank()) {
            try {
                LocalDateTime end = java.time.LocalDate.parse(endDate.trim()).atTime(23, 59, 59);
                specification = specification.and((root, query, cb) ->
                    cb.lessThanOrEqualTo(root.get("createdAt"), end)
                );
            } catch (Exception ignored) {
                // Invalid date format, skip filter
            }
        }

        return specification;
    }

    private ContactProfileResponse mapToResponse(ContactProfile profile) {
        String contactName = firstNonBlank(
            profile.getContactName(),
            combineNames(profile.getRepresentativeFirstName(), profile.getRepresentativeLastName()),
            profile.getBillingName(),
            profile.getName()
        );

        String contactEmail = firstNonBlank(
            profile.getEmailPrimary(),
            profile.getEmailSecondary(),
            profile.getEmailTertiary(),
            profile.getRepresentativeEmail()
        );

        String phone = firstNonBlank(
            profile.getPhonePrimary(),
            profile.getPhoneSecondary(),
            profile.getPhoneTertiary(),
            profile.getPhoneQuaternary(),
            profile.getRepresentativePhone()
        );

        String plan = firstNonBlank(profile.getAssignment(), profile.getCategory(), profile.getTenantType(), "Custom");
        String center = profile.getCenterId() != null ? String.valueOf(profile.getCenterId()) : null;
        String userType = firstNonBlank(profile.getTenantType(), profile.getCategory(), profile.getAssignment(), "—");
        String status = resolveStatus(profile);
        Double usage = 0.0d;
        Integer seats = 0;
        String lastActive = formatLastActive(profile);
        String createdAt = profile.getCreatedAt() != null ? CREATED_AT_FORMATTER.format(profile.getCreatedAt()) : null;
        String channel = firstNonBlank(profile.getChannel(), "—");

        ContactProfileResponse.Contact contact = new ContactProfileResponse.Contact(contactName, contactEmail);
        ContactProfileResponse.Billing billing = new ContactProfileResponse.Billing(
            firstNonBlank(profile.getBillingName(), profile.getName()),
            firstNonBlank(profile.getEmailPrimary(), profile.getEmailSecondary(), profile.getEmailTertiary()),
            profile.getBillingAddress(),
            profile.getBillingPostalCode(),
            firstNonBlank(profile.getBillingProvince(), profile.getBillingCity()),
            profile.getBillingCountry(),
            profile.getBillingTaxId()
        );

        return new ContactProfileResponse(
            profile.getId(),
            profile.getName(),
            contact,
            plan,
            center,
            userType,
            status,
            seats,
            usage,
            lastActive,
            channel,
            createdAt,
            phone,
            profile.getAvatar(),
            billing
        );
    }

    private String resolveStatus(ContactProfile profile) {
        if (profile.getStatus() != null && !profile.getStatus().isBlank()) {
            return profile.getStatus();
        }
        if (profile.getActive() != null) {
            return profile.getActive() ? "Active" : "Inactive";
        }
        return "Unknown";
    }

    private String formatLastActive(ContactProfile profile) {
        LocalDateTime reference = firstNonNull(profile.getStatusChangedAt(), profile.getOnboardedAt(), profile.getCreatedAt());
        if (reference == null) {
            return null;
        }

        Duration duration = Duration.between(reference, LocalDateTime.now());
        if (!duration.isNegative()) {
            long minutes = duration.toMinutes();
            if (minutes < 1) {
                return "Just now";
            } else if (minutes < 60) {
                return minutes + "m ago";
            }
            long hours = duration.toHours();
            if (hours < 24) {
                return hours + "h ago";
            }
            long days = duration.toDays();
            if (days < 7) {
                return days + "d ago";
            }
        }
        return LAST_ACTIVE_DATE_FORMATTER.format(reference);
    }

    @SafeVarargs
    private static <T> T firstNonNull(T... values) {
        for (T value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        return Stream.of(values)
            .filter(Objects::nonNull)
            .map(String::trim)
            .filter(value -> !value.isEmpty())
            .findFirst()
            .orElse(null);
    }

    private static String combineNames(String firstName, String lastName) {
        if ((firstName == null || firstName.isBlank()) && (lastName == null || lastName.isBlank())) {
            return null;
        }
        if (firstName == null || firstName.isBlank()) {
            return lastName;
        }
        if (lastName == null || lastName.isBlank()) {
            return firstName;
        }
        return firstName + " " + lastName;
    }

    private String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private Long parseCenterId(String value) {
        String trimmed = blankToNull(value);
        if (trimmed == null) {
            return null;
        }
        try {
            return Long.parseLong(trimmed);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    @Transactional
    public ContactProfile createContactProfile(ContactProfileRequest request) {
        ContactProfile profile = new ContactProfile();
        
        // Generate a new ID (using current timestamp as base)
        Long newId = System.currentTimeMillis();
        profile.setId(newId);
        
        // Set basic information
        profile.setName(request.getName());
        profile.setEmailPrimary(request.getEmail());
        profile.setContactName(request.getPrimaryContact());
        profile.setPhonePrimary(request.getPhone());
        profile.setStatus(request.getStatus() != null ? request.getStatus() : "Potencial");
        profile.setTenantType(request.getUserType());
        profile.setCenterId(request.getCenter() != null ? Long.parseLong(request.getCenter()) : null);
        profile.setChannel(request.getChannel());
        profile.setAvatar(request.getAvatar());
        
        // Set billing information
        profile.setBillingName(request.getBillingCompany());
        profile.setEmailSecondary(request.getBillingEmail());
        profile.setBillingAddress(request.getBillingAddress());
        profile.setBillingPostalCode(request.getBillingPostalCode());
        profile.setBillingProvince(request.getBillingCounty());
        profile.setBillingCountry(request.getBillingCountry());
        
        // Set default values
        profile.setActive(true);
        profile.setCreatedAt(LocalDateTime.now());
        profile.setStatusChangedAt(LocalDateTime.now());
        
        // Save the profile
        ContactProfile savedProfile = repository.save(profile);
        
        // Sync avatar to user table if this contact profile belongs to a user
        if (savedProfile.getAvatar() != null && savedProfile.getEmailPrimary() != null) {
            System.out.println("DEBUG: Creating contact profile, syncing avatar for email: " + savedProfile.getEmailPrimary());
            userRepository.findByEmail(savedProfile.getEmailPrimary())
                .ifPresent(user -> {
                    System.out.println("DEBUG: Found user during creation, updating avatar from: " + user.getAvatar() + " to: " + savedProfile.getAvatar());
                    user.setAvatar(savedProfile.getAvatar());
                    userRepository.save(user);
                    System.out.println("DEBUG: User avatar updated successfully during creation");
                });
        }
        
        return savedProfile;
    }

    @Transactional
    public ContactProfile updateContactProfile(Long id, ContactProfileRequest request) {
        ContactProfile profile = repository.findById(id)
            .orElseThrow(() -> new ContactProfileNotFoundException(id));

        // Track original status to update timestamp if needed
        String originalStatus = profile.getStatus();

        if (request.getName() != null && !request.getName().isBlank()) {
            profile.setName(request.getName().trim());
        }

        if (request.getEmail() != null && !request.getEmail().isBlank()) {
            profile.setEmailPrimary(request.getEmail().trim());
        }

        if (request.getPrimaryContact() != null) {
            profile.setContactName(blankToNull(request.getPrimaryContact()));
        }

        if (request.getPhone() != null) {
            profile.setPhonePrimary(blankToNull(request.getPhone()));
        }

        System.out.println("DEBUG: Avatar request value: " + request.getAvatar());
        System.out.println("DEBUG: Avatar request is null: " + (request.getAvatar() == null));
        
        if (request.getAvatar() != null) {
            System.out.println("DEBUG: Avatar is not null, updating profile avatar");
            profile.setAvatar(blankToNull(request.getAvatar()));
            
            // Sync avatar to user table if this contact profile belongs to a user
            if (profile.getEmailPrimary() != null) {
                System.out.println("DEBUG: Syncing avatar for email: " + profile.getEmailPrimary());
                userRepository.findByEmail(profile.getEmailPrimary())
                    .ifPresent(user -> {
                        System.out.println("DEBUG: Found user, updating avatar from: " + user.getAvatar() + " to: " + profile.getAvatar());
                        user.setAvatar(profile.getAvatar());
                        userRepository.save(user);
                        System.out.println("DEBUG: User avatar updated successfully");
                    });
            } else {
                System.out.println("DEBUG: No email found for contact profile, skipping avatar sync");
            }
        } else {
            System.out.println("DEBUG: Avatar is null, skipping avatar update");
        }

        if (request.getStatus() != null) {
            String normalizedStatus = blankToNull(request.getStatus());
            profile.setStatus(normalizedStatus);
            if (!Objects.equals(originalStatus, normalizedStatus)) {
                profile.setStatusChangedAt(LocalDateTime.now());
            }
        }

        if (request.getUserType() != null) {
            profile.setTenantType(blankToNull(request.getUserType()));
        }

        if (request.getCenter() != null) {
            profile.setCenterId(parseCenterId(request.getCenter()));
        }

        if (request.getChannel() != null) {
            profile.setChannel(blankToNull(request.getChannel()));
        }

        // Billing block
        if (request.getBillingCompany() != null) {
            profile.setBillingName(blankToNull(request.getBillingCompany()));
        }
        if (request.getBillingEmail() != null) {
            profile.setEmailSecondary(blankToNull(request.getBillingEmail()));
        }
        if (request.getBillingAddress() != null) {
            profile.setBillingAddress(blankToNull(request.getBillingAddress()));
        }
        if (request.getBillingPostalCode() != null) {
            profile.setBillingPostalCode(blankToNull(request.getBillingPostalCode()));
        }
        if (request.getBillingCounty() != null) {
            profile.setBillingProvince(blankToNull(request.getBillingCounty()));
        }
        if (request.getBillingCountry() != null) {
            profile.setBillingCountry(blankToNull(request.getBillingCountry()));
        }

        return repository.save(profile);
    }

    @Transactional
    public boolean deleteContactProfile(Long id) {
        try {
            // Check if the profile exists
            if (!repository.existsById(id)) {
                return false;
            }
            
            // Delete related records first to avoid foreign key constraints
            // Delete from bloqueos table first (if it exists)
            try {
                entityManager.createNativeQuery("DELETE FROM beworking.bloqueos WHERE id_cliente = ?")
                    .setParameter(1, id)
                    .executeUpdate();
            } catch (Exception e) {
                // Table might not exist or no records, continue
            }
            
            // Delete from reservas table
            try {
                entityManager.createNativeQuery("DELETE FROM beworking.reservas WHERE id_cliente = ?")
                    .setParameter(1, id)
                    .executeUpdate();
            } catch (Exception e) {
                // Table might not exist or no records, continue
            }
            
            // Delete from facturasdesglose table (if it exists)
            try {
                entityManager.createNativeQuery("DELETE FROM beworking.facturasdesglose WHERE idbloqueovinculado IN (SELECT id FROM beworking.bloqueos WHERE id_cliente = ?)")
                    .setParameter(1, id)
                    .executeUpdate();
            } catch (Exception e) {
                // Table might not exist or no records, continue
            }
            
            // Now delete the profile
            repository.deleteById(id);
            return true;
        } catch (Exception e) {
            // Log the error if needed
            return false;
        }
    }

    @Transactional(readOnly = true)
    public ContactProfile getContactProfileById(Long id) {
        return repository.findById(id)
            .orElseThrow(() -> new ContactProfileNotFoundException(id));
    }
}
