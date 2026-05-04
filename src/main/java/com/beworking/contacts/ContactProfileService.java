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
import com.beworking.auth.RegisterService;
import com.beworking.auth.UserRepository;
import com.beworking.bookings.CentroRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestTemplate;

@Service
public class ContactProfileService {

    private static final Logger LOGGER = LoggerFactory.getLogger(ContactProfileService.class);

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
    private final ViesVatService viesVatService;
    private final CentroRepository centroRepository;
    private final RegisterService registerService;
    private final org.springframework.context.ApplicationEventPublisher eventPublisher;

    public ContactProfileService(ContactProfileRepository repository, UserRepository userRepository,
                                  ViesVatService viesVatService, CentroRepository centroRepository,
                                  RegisterService registerService,
                                  org.springframework.context.ApplicationEventPublisher eventPublisher) {
        this.repository = repository;
        this.userRepository = userRepository;
        this.viesVatService = viesVatService;
        this.centroRepository = centroRepository;
        this.registerService = registerService;
        this.eventPublisher = eventPublisher;
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
            specification = specification.and((root, query, cb) -> {
                // Use unaccent() for accent-insensitive search (e.g. "area" matches "Área")
                var unaccentPattern = cb.function("unaccent", String.class, cb.literal(likePattern));
                return cb.or(
                    cb.like(cb.function("unaccent", String.class, cb.lower(root.get("name"))), unaccentPattern),
                    cb.like(cb.function("unaccent", String.class, cb.lower(root.get("contactName"))), unaccentPattern),
                    cb.like(cb.function("unaccent", String.class, cb.lower(root.get("billingName"))), unaccentPattern),
                    cb.like(cb.lower(root.get("emailPrimary")), likePattern),
                    cb.like(cb.lower(root.get("emailSecondary")), likePattern),
                    cb.like(cb.lower(root.get("emailTertiary")), likePattern)
                );
            });
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
        if (contactEmail == null) {
            contactEmail = userRepository.findFirstByTenantIdOrderByIdAsc(profile.getId())
                .map(com.beworking.auth.User::getEmail)
                .orElse(null);
        }

        String phone = firstNonBlank(
            profile.getPhonePrimary(),
            profile.getPhoneSecondary(),
            profile.getPhoneTertiary(),
            profile.getPhoneQuaternary(),
            profile.getRepresentativePhone()
        );

        String plan = firstNonBlank(profile.getAssignment(), profile.getCategory(), profile.getTenantType(), "Custom");
        // Return the dashboard's "CODE - NAME" combined format (e.g. "MA1 - MALAGA DUMAS")
        // so the edit form's dropdown can round-trip the value cleanly.
        String center = profile.getCenterId() != null
            ? centroRepository.findById(profile.getCenterId())
                .map(c -> {
                    String code = c.getCodigo() != null ? c.getCodigo().trim() : "";
                    String nombre = c.getNombre() != null ? c.getNombre().trim() : "";
                    return code.isEmpty() ? nombre : (code + " - " + nombre).trim();
                })
                .orElse(null)
            : null;
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
            null,
            profile.getBillingAddress(),
            profile.getBillingPostalCode(),
            profile.getBillingProvince(),
            profile.getBillingCity(),
            profile.getBillingCountry(),
            profile.getBillingTaxId(),
            profile.getBillingTaxIdType(),
            profile.getVatValid()
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
        // Try numeric ID first
        try {
            return Long.parseLong(trimmed);
        } catch (NumberFormatException ignored) {
            // fall through to name/code lookup
        }
        // Try matching by full nombre (e.g. "MALAGA DUMAS")
        var byName = centroRepository.findByNombreIgnoreCase(trimmed);
        if (byName.isPresent()) return byName.get().getId();
        // Try matching by codigo (e.g. "MA1")
        var byCode = centroRepository.findByCodigoIgnoreCase(trimmed);
        if (byCode.isPresent()) return byCode.get().getId();
        // Try the dashboard's combined "CODE - NAME" format (e.g. "MA1 - MALAGA DUMAS").
        // The dropdown labels include the codigo prefix for human readability,
        // but the centros table stores codigo and nombre separately.
        // Also tolerate the older "CODE NAME" form (no dash) for safety.
        String dashSplit = trimmed;
        int dashIdx = trimmed.indexOf(" - ");
        if (dashIdx > 0) {
            String left = trimmed.substring(0, dashIdx).trim();
            String right = trimmed.substring(dashIdx + 3).trim();
            var byCodeLeft = centroRepository.findByCodigoIgnoreCase(left);
            if (byCodeLeft.isPresent()) return byCodeLeft.get().getId();
            var byNameRight = centroRepository.findByNombreIgnoreCase(right);
            if (byNameRight.isPresent()) return byNameRight.get().getId();
        }
        String[] parts = dashSplit.split("\\s+", 2);
        if (parts.length == 2) {
            var byCodeFirst = centroRepository.findByCodigoIgnoreCase(parts[0]);
            if (byCodeFirst.isPresent()) return byCodeFirst.get().getId();
            var byNameRest = centroRepository.findByNombreIgnoreCase(parts[1]);
            if (byNameRest.isPresent()) return byNameRest.get().getId();
        }
        return null;
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
        profile.setCenterId(parseCenterId(request.getCenter()));
        profile.setChannel(request.getChannel());
        profile.setAvatar(request.getAvatar());
        
        // Set billing information
        profile.setBillingName(request.getBillingCompany());
        profile.setBillingTaxId(request.getBillingTaxId());
        profile.setBillingTaxIdType(blankToNull(request.getBillingTaxIdType()));
        profile.setEmailSecondary(request.getBillingEmail());
        profile.setBillingAddress(request.getBillingAddress());
        profile.setBillingPostalCode(request.getBillingPostalCode());
        profile.setBillingCity(request.getBillingCity());
        profile.setBillingProvince(request.getBillingCounty());
        profile.setBillingCountry(request.getBillingCountry());

        runVatValidation(profile);

        // Set default values
        profile.setActive(true);
        profile.setCreatedAt(LocalDateTime.now());
        profile.setStatusChangedAt(LocalDateTime.now());

        // Save the profile
        ContactProfile savedProfile = repository.save(profile);

        // Lead → customer conversion: any matching lead is removed after the
        // transaction commits (handled by LeadCleanupListener).
        eventPublisher.publishEvent(new ContactProfileCreatedEvent(
            savedProfile.getId(), savedProfile.getEmailPrimary()));

        // Auto-create or link user account for this contact
        if (savedProfile.getEmailPrimary() != null && !savedProfile.getEmailPrimary().isBlank()) {
            String contactName = savedProfile.getContactName() != null && !savedProfile.getContactName().isBlank()
                ? savedProfile.getContactName() : savedProfile.getName();
            com.beworking.auth.User user = registerService.createUserForContact(
                savedProfile.getEmailPrimary(), contactName, savedProfile.getId());
            if (user != null && savedProfile.getAvatar() != null) {
                user.setAvatar(savedProfile.getAvatar());
                userRepository.save(user);
            }
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
            String newEmail = request.getEmail().trim();
            profile.setEmailPrimary(newEmail);
        }

        if (request.getPrimaryContact() != null) {
            profile.setContactName(blankToNull(request.getPrimaryContact()));
        }

        if (request.getPhone() != null) {
            profile.setPhonePrimary(blankToNull(request.getPhone()));
        }

        if (request.getAvatar() != null) {
            profile.setAvatar(blankToNull(request.getAvatar()));
        }

        // Sync key fields to linked user account
        userRepository.findFirstByTenantIdOrderByIdAsc(profile.getId())
            .ifPresent(user -> {
                boolean changed = false;
                if (request.getEmail() != null && !request.getEmail().isBlank()) {
                    String newEmail = request.getEmail().trim().toLowerCase();
                    if (!newEmail.equalsIgnoreCase(user.getEmail())) {
                        user.setEmail(newEmail);
                        changed = true;
                    }
                }
                String syncName = profile.getContactName() != null && !profile.getContactName().isBlank()
                    ? profile.getContactName() : profile.getName();
                if (syncName != null && !syncName.equals(user.getName())) {
                    user.setName(syncName);
                    changed = true;
                }
                if (profile.getPhonePrimary() != null && !profile.getPhonePrimary().equals(user.getPhone())) {
                    user.setPhone(profile.getPhonePrimary());
                    changed = true;
                }
                if (profile.getAvatar() != null && !profile.getAvatar().equals(user.getAvatar())) {
                    user.setAvatar(profile.getAvatar());
                    changed = true;
                }
                if (changed) {
                    userRepository.save(user);
                }
            });

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
            String rawCenter = request.getCenter();
            if (rawCenter.trim().isEmpty()) {
                // Empty string from the form clears the center.
                profile.setCenterId(null);
            } else {
                Long resolved = parseCenterId(rawCenter);
                if (resolved != null) {
                    profile.setCenterId(resolved);
                } else {
                    // Lookup failed (no matching centro by name/code/id). Don't
                    // silently overwrite the existing value with NULL — that's
                    // the bug that wiped center_id during routine edits. Log
                    // and keep the existing assignment.
                    LOGGER.warn("Could not resolve center '{}' for contact {} — keeping existing center_id={}",
                        rawCenter, profile.getId(), profile.getCenterId());
                }
            }
        }

        if (request.getChannel() != null) {
            profile.setChannel(blankToNull(request.getChannel()));
        }

        // Billing block
        if (request.getBillingCompany() != null) {
            profile.setBillingName(blankToNull(request.getBillingCompany()));
        }
        boolean taxIdChanged = false;
        if (request.getBillingTaxId() != null) {
            String newTaxId = blankToNull(request.getBillingTaxId());
            profile.setBillingTaxId(newTaxId);
            taxIdChanged = true;
        }
        if (request.getBillingTaxIdType() != null) {
            // Accept any non-blank string; valid values are es_cif | es_nif | eu_vat | no_vat.
            // Setting null clears it.
            profile.setBillingTaxIdType(blankToNull(request.getBillingTaxIdType()));
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
        if (request.getBillingCity() != null) {
            profile.setBillingCity(blankToNull(request.getBillingCity()));
        }
        if (request.getBillingCounty() != null) {
            profile.setBillingProvince(blankToNull(request.getBillingCounty()));
        }
        boolean countryChanged = false;
        if (request.getBillingCountry() != null) {
            profile.setBillingCountry(blankToNull(request.getBillingCountry()));
            countryChanged = true;
        }

        if (taxIdChanged || countryChanged) {
            runVatValidation(profile);
        }

        return repository.save(profile);
    }

    private static final java.util.Set<String> EU_VAT_PREFIXES = java.util.Set.of(
        "AT","BE","BG","CY","CZ","DE","DK","EE","EL","ES","FI","FR",
        "HR","HU","IE","IT","LT","LU","LV","MT","NL","PL","PT","RO",
        "SE","SI","SK","XI"
    );
    private static final java.time.Duration VAT_CACHE_TTL = java.time.Duration.ofDays(7);

    /**
     * Runs VIES validation for the contact and stores a definitive TRUE/FALSE
     * in vat_valid + vat_validated_at = now().
     *
     * Country resolution (in order):
     *   1. EU country prefix in the tax ID itself (e.g., "ES B72959687").
     *   2. billing_country mapped to ISO-2 via {@link ViesVatService#countryNameToIso}.
     *   3. ES default — BeWorking is Spain-based; bare NIF/CIF without country
     *      context is overwhelmingly Spanish (autónomo or empresa). Spanish
     *      autónomos with personal-NIF format are validated by VIES too.
     *
     * Result is always TRUE or FALSE when a tax ID is present; only NULL when
     * the contact has no tax ID at all. VIES network errors → FALSE so the
     * caller never reverse-charges based on an unknown state.
     */
    private void runVatValidation(ContactProfile profile) {
        profile.setVatValidatedAt(LocalDateTime.now());
        String taxId = profile.getBillingTaxId();
        if (taxId == null || taxId.isBlank()) {
            profile.setVatValid(null);
            return;
        }
        String countryHint = resolveCountryHint(profile);
        try {
            ViesVatService.VatValidationResult result = viesVatService.validate(taxId, countryHint);
            applyValidationResult(profile, result.valid());
        } catch (Exception e) {
            // Network / timeout / SOAP error. NEVER write vat_valid=FALSE here —
            // doing so silently corrupts legitimate B2B customers every time VIES
            // hiccups. Just record the failure; next successful call settles truth.
            LOGGER.warn("VIES error for contact {} (taxId={}, hint={}): {} — keeping vat_valid={} as-is",
                profile.getId(), taxId, countryHint, e.getMessage(), profile.getVatValid());
            bumpFailureStreak(profile);
        }
    }

    /**
     * Apply a definitive VIES result with stickiness:
     *  - VALID → write TRUE, reset streak, flag status_changed_at if changed.
     *  - INVALID + currently TRUE → bump streak; only flip to FALSE on 2nd
     *    consecutive confirmed-invalid result. Protects against one-off
     *    VIES anomalies where the customer is genuinely registered.
     *  - INVALID + currently NULL/FALSE → write FALSE.
     */
    private void applyValidationResult(ContactProfile profile, boolean viesValid) {
        LocalDateTime now = LocalDateTime.now();
        Boolean current = profile.getVatValid();

        if (viesValid) {
            profile.setVatFailureStreak(0);
            profile.setVatLastFailureAt(null);
            if (!Boolean.TRUE.equals(current)) {
                profile.setVatValid(true);
                profile.setVatStatusChangedAt(now);
            }
            return;
        }

        if (Boolean.TRUE.equals(current)) {
            bumpFailureStreak(profile);
            Integer streak = profile.getVatFailureStreak();
            if (streak != null && streak >= 2) {
                profile.setVatValid(false);
                profile.setVatStatusChangedAt(now);
            }
            // else: keep TRUE for now; one negative isn't enough.
        } else {
            profile.setVatValid(false);
            if (current == null) profile.setVatStatusChangedAt(now);
        }
    }

    private void bumpFailureStreak(ContactProfile profile) {
        Integer s = profile.getVatFailureStreak();
        profile.setVatFailureStreak((s == null ? 0 : s) + 1);
        profile.setVatLastFailureAt(LocalDateTime.now());
    }

    private static String resolveCountryHint(ContactProfile profile) {
        String taxId = profile.getBillingTaxId();
        if (taxId != null && !taxId.isBlank()) {
            String normalized = taxId.trim().replaceAll("\\s+", "").toUpperCase();
            if (normalized.length() >= 2) {
                String maybePrefix = normalized.substring(0, 2);
                if (EU_VAT_PREFIXES.contains(maybePrefix)) {
                    return maybePrefix;
                }
            }
        }
        String fromBillingCountry = ViesVatService.countryNameToIso(profile.getBillingCountry());
        if (fromBillingCountry != null) return fromBillingCountry;
        return "ES";
    }

    @Transactional
    public boolean revalidateVat(Long contactId) {
        ContactProfile profile = repository.findById(contactId).orElse(null);
        if (profile == null) return false;
        runVatValidation(profile);
        repository.save(profile);
        return Boolean.TRUE.equals(profile.getVatValid());
    }

    /**
     * Just-in-time VIES validation. Cached for {@link #VAT_CACHE_TTL}; older or
     * never-validated entries are re-checked against VIES and persisted. Used
     * by every VAT-resolution path so the rate is always computed from the
     * latest definitive state — never NULL.
     */
    @Transactional
    public Boolean ensureVatValidated(Long contactId) {
        if (contactId == null) return null;
        ContactProfile profile = repository.findById(contactId).orElse(null);
        if (profile == null) return null;
        if (profile.getVatValid() != null
            && profile.getVatValidatedAt() != null
            && profile.getVatValidatedAt().isAfter(LocalDateTime.now().minus(VAT_CACHE_TTL))) {
            return profile.getVatValid();
        }
        runVatValidation(profile);
        repository.save(profile);
        return profile.getVatValid();
    }

    /**
     * One-shot bulk re-validation against VIES. Throttled to ~1 req/sec to stay
     * under the implicit VIES rate limit. Skips contacts already known TRUE so
     * the 15 confirmed-good ones aren't re-burned. Use when seeding fresh data
     * after a validator-logic fix or DB restore.
     *
     * NOT @Transactional — running for ~33 min in one transaction would hold a
     * single connection open the whole time and only commit at the end. Each
     * repository.save() below is its own short transaction (Spring Data JPA's
     * SimpleJpaRepository wraps save() in @Transactional internally), so
     * progress lands on disk per contact and is visible to other sessions.
     */
    public java.util.Map<String, Integer> revalidateAllStaleVat() {
        java.util.List<ContactProfile> all = repository.findAll();
        int processed = 0, validated = 0, invalid = 0, errors = 0;
        for (ContactProfile p : all) {
            if (p.getBillingTaxId() == null || p.getBillingTaxId().isBlank()) continue;
            if (Boolean.TRUE.equals(p.getVatValid())) continue;
            try {
                runVatValidation(p);
                repository.save(p);  // its own short transaction → commits now
                processed++;
                if (Boolean.TRUE.equals(p.getVatValid())) validated++; else invalid++;
            } catch (Exception e) {
                errors++;
                LOGGER.warn("Reseed failed for contact {}: {}", p.getId(), e.getMessage());
            }
            // VIES rate-limit safety: ~1 req/sec. 1972 contacts ≈ 33 min worst case.
            try { Thread.sleep(1000); } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return java.util.Map.of(
            "processed", processed,
            "validated", validated,
            "invalid", invalid,
            "errors", errors);
    }

    @Transactional
    public boolean deleteContactProfile(Long id) {
        if (!repository.existsById(id)) {
            return false;
        }

        // facturasdesglose must be deleted BEFORE bloqueos (FK on idbloqueovinculado)
        try {
            entityManager.createNativeQuery(
                "DELETE FROM beworking.facturasdesglose WHERE idbloqueovinculado IN (SELECT id FROM beworking.bloqueos WHERE id_cliente = ?)")
                .setParameter(1, id)
                .executeUpdate();
        } catch (Exception e) {
            // no records or table doesn't exist
        }

        try {
            entityManager.createNativeQuery("DELETE FROM beworking.bloqueos WHERE id_cliente = ?")
                .setParameter(1, id)
                .executeUpdate();
        } catch (Exception e) {
            // no records or table doesn't exist
        }

        try {
            entityManager.createNativeQuery("DELETE FROM beworking.reservas WHERE id_cliente = ?")
                .setParameter(1, id)
                .executeUpdate();
        } catch (Exception e) {
            // no records or table doesn't exist
        }

        // Cancel Stripe subscriptions before deleting from DB
        try {
            @SuppressWarnings("unchecked")
            List<String> stripeSubIds = entityManager.createNativeQuery(
                "SELECT stripe_subscription_id FROM beworking.subscriptions WHERE contact_id = ?")
                .setParameter(1, id)
                .getResultList();
            if (!stripeSubIds.isEmpty()) {
                String stripeServiceUrl = System.getenv("STRIPE_SERVICE_URL") != null
                    ? System.getenv("STRIPE_SERVICE_URL")
                    : "http://beworking-stripe-service:8081";
                RestTemplate restTemplate = new RestTemplate();
                for (String subId : stripeSubIds) {
                    try {
                        restTemplate.delete(stripeServiceUrl + "/api/subscriptions/" + subId + "?tenant=beworking");
                        LOGGER.info("Cancelled Stripe subscription {} for contact {}", subId, id);
                    } catch (Exception e) {
                        LOGGER.error("Failed to cancel Stripe subscription {} for contact {} — manual cancellation required: {}", subId, id, e.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.error("Failed to look up Stripe subscriptions for contact {}: {}", id, e.getMessage());
        }

        try {
            entityManager.createNativeQuery("DELETE FROM beworking.subscriptions WHERE contact_id = ?")
                .setParameter(1, id)
                .executeUpdate();
        } catch (Exception e) {
            // no records or table doesn't exist
        }

        // Delete linked user account (matched by email_primary) so the email can be re-registered
        try {
            entityManager.createNativeQuery(
                "DELETE FROM beworking.users WHERE email = (SELECT email_primary FROM beworking.contact_profiles WHERE id = ?)")
                .setParameter(1, id)
                .executeUpdate();
        } catch (Exception e) {
            // no linked user account
        }

        repository.deleteById(id);
        return true;
    }

    @Transactional(readOnly = true)
    public ContactProfile getContactProfileById(Long id) {
        return repository.findById(id)
            .orElseThrow(() -> new ContactProfileNotFoundException(id));
    }
}
