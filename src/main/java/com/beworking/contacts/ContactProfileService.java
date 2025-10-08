package com.beworking.contacts;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

@Service
public class ContactProfileService {

    private static final DateTimeFormatter CREATED_AT_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    @PersistenceContext
    private EntityManager entityManager;
    private static final DateTimeFormatter LAST_ACTIVE_DATE_FORMATTER = DateTimeFormatter.ofPattern("MMM dd yyyy");

    private final ContactProfileRepository repository;

    public ContactProfileService(ContactProfileRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public ContactProfilesPageResponse getContactProfiles(int page, int size, String search, String status, String plan, String tenantType, String email) {
        int pageIndex = Math.max(page, 0);
        int pageSize = Math.max(1, Math.min(size, 100));

        Specification<ContactProfile> specification = buildSpecification(search, status, plan, tenantType, email);
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

    private Specification<ContactProfile> buildSpecification(String search, String status, String plan, String tenantType, String email) {
        Specification<ContactProfile> specification = Specification.where(null);

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
        
        return savedProfile;
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
}
