package com.beworking.contacts;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import com.beworking.auth.RegisterService;
import com.beworking.auth.User;
import com.beworking.auth.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@RestController
@RequestMapping("/api/contact-profiles")
@CrossOrigin(origins = {"http://localhost:5173", "http://localhost:5174"})
public class ContactProfileController {

    private static final Logger logger = LoggerFactory.getLogger(ContactProfileController.class);
    private final ContactProfileService contactProfileService;
    private final UserRepository userRepository;
    private final JdbcTemplate jdbcTemplate;
    private final ViesVatService viesVatService;
    private final RegisterService registerService;

    public ContactProfileController(ContactProfileService contactProfileService,
                                     UserRepository userRepository,
                                     JdbcTemplate jdbcTemplate,
                                     ViesVatService viesVatService,
                                     RegisterService registerService) {
        this.contactProfileService = contactProfileService;
        this.userRepository = userRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.viesVatService = viesVatService;
        this.registerService = registerService;
    }

    @GetMapping
    public ResponseEntity<ContactProfilesPageResponse> getAllContactProfiles(
        Authentication authentication,
        @RequestParam(value = "page", defaultValue = "0") int page,
        @RequestParam(value = "size", defaultValue = "25") int size,
        @RequestParam(value = "search", required = false) String search,
        @RequestParam(value = "status", required = false) String status,
        @RequestParam(value = "plan", required = false) String plan,
        @RequestParam(value = "tenantType", required = false) String tenantType,
        @RequestParam(value = "email", required = false) String email,
        @RequestParam(value = "startDate", required = false) String startDate,
        @RequestParam(value = "endDate", required = false) String endDate
    ) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        String userEmail = authentication.getName();
        User user = userRepository.findByEmail(userEmail).orElse(null);
        
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        // For users, filter by their tenantId to show only their own contact
        // For admins, show all contacts
        System.out.println("DEBUG: User role: " + user.getRole() + " (USER: " + User.Role.USER + ", ADMIN: " + User.Role.ADMIN + ")");
        System.out.println("DEBUG: Role comparison: " + (user.getRole() == User.Role.USER));
        if (user.getRole() == User.Role.USER) {
            // Users can only see their own contact
            if (user.getTenantId() != null) {
                // Filter to only show the user's own contact
                ContactProfilesPageResponse profiles = contactProfileService.getContactProfilesByTenantId(
                    user.getTenantId(), page, size, search, status, plan, tenantType, email, startDate, endDate
                );
                return ResponseEntity.ok(profiles);
            } else {
                // User has no tenantId, try to find their contact by email
                ContactProfilesPageResponse profiles = contactProfileService.getContactProfilesByEmail(
                    user.getEmail(), page, size, search, status, plan, tenantType, email, startDate, endDate
                );
                return ResponseEntity.ok(profiles);
            }
        } else {
            // Admins can see all contacts
            ContactProfilesPageResponse profiles = contactProfileService.getContactProfiles(page, size, search, status, plan, tenantType, email, startDate, endDate);
            return ResponseEntity.ok(profiles);
        }
    }

    @GetMapping("/{id}")
    public ResponseEntity<ContactProfile> getContactProfileById(
        @PathVariable Long id,
        Authentication authentication
    ) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        String userEmail = authentication.getName();
        User user = userRepository.findByEmail(userEmail).orElse(null);
        
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        try {
            ContactProfile profile = contactProfileService.getContactProfileById(id);
            
            // For users, only allow access to their own contact
            if (user.getRole() == User.Role.USER) {
                if (user.getTenantId() != null && !user.getTenantId().equals(profile.getId())) {
                    return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
                }
            }
            
            return ResponseEntity.ok(profile);
        } catch (ContactProfileService.ContactProfileNotFoundException e) {
            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @PostMapping
    public ResponseEntity<ContactProfile> createContactProfile(@RequestBody ContactProfileRequest request) {
        try {
            ContactProfile createdProfile = contactProfileService.createContactProfile(request);
            return ResponseEntity.status(HttpStatus.CREATED).body(createdProfile);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @PostMapping("/sync-users")
    public ResponseEntity<Map<String, Object>> syncContactUsers(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        User adminUser = userRepository.findByEmail(authentication.getName()).orElse(null);
        if (adminUser == null || adminUser.getRole() != User.Role.ADMIN) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        List<Map<String, Object>> contacts = jdbcTemplate.queryForList(
            "SELECT id, email_primary, contact_name, name FROM beworking.contact_profiles WHERE COALESCE(TRIM(email_primary), '') != '' ORDER BY id");

        int created = 0;
        int alreadyLinked = 0;
        int skipped = 0;
        java.util.ArrayList<String> errors = new java.util.ArrayList<>();

        for (Map<String, Object> cp : contacts) {
            String email = (String) cp.get("email_primary");
            if (email == null || email.isBlank()) {
                skipped++;
                continue;
            }
            try {
                var existing = userRepository.findByEmail(email.trim().toLowerCase());
                if (existing.isPresent()) {
                    User user = existing.get();
                    if (user.getTenantId() == null) {
                        user.setTenantId((Long) cp.get("id"));
                        userRepository.save(user);
                    }
                    alreadyLinked++;
                } else {
                    String name = cp.get("contact_name") != null && !((String) cp.get("contact_name")).isBlank()
                        ? (String) cp.get("contact_name") : (String) cp.get("name");
                    registerService.createUserForContactSilent(email, name, (Long) cp.get("id"));
                    created++;
                }
            } catch (Exception e) {
                errors.add(cp.get("id") + ": " + e.getMessage());
            }
        }

        Map<String, Object> result = new HashMap<>();
        result.put("created", created);
        result.put("alreadyLinked", alreadyLinked);
        result.put("skipped", skipped);
        result.put("total", contacts.size());
        if (!errors.isEmpty()) {
            result.put("errors", errors);
        }
        return ResponseEntity.ok(result);
    }

    @PutMapping("/{id}")
    public ResponseEntity<ContactProfile> updateContactProfile(
        @PathVariable Long id,
        @RequestBody ContactProfileRequest request
    ) {
        try {
            ContactProfile updatedProfile = contactProfileService.updateContactProfile(id, request);
            return ResponseEntity.ok(updatedProfile);
        } catch (ContactProfileService.ContactProfileNotFoundException e) {
            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            logger.error("Failed to update contact profile {}: {}", id, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @GetMapping("/audit")
    public ResponseEntity<Map<String, Object>> auditContacts(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        User user = userRepository.findByEmail(authentication.getName()).orElse(null);
        if (user == null || user.getRole() != User.Role.ADMIN) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        // Contacts with no name (name, contact_name, and billing_name all empty)
        List<Map<String, Object>> noName = jdbcTemplate.queryForList("""
            SELECT c.id, c.email_primary, c.status, c.tenant_type, c.created_at
            FROM beworking.contact_profiles c
            WHERE COALESCE(TRIM(c.name), '') = ''
              AND COALESCE(TRIM(c.contact_name), '') = ''
              AND COALESCE(TRIM(c.billing_name), '') = ''
            ORDER BY c.id DESC
            """);

        // Contacts with no email (all 4 email fields empty)
        List<Map<String, Object>> noEmail = jdbcTemplate.queryForList("""
            SELECT c.id, c.name, c.contact_name, c.status, c.tenant_type, c.created_at
            FROM beworking.contact_profiles c
            WHERE COALESCE(TRIM(c.email_primary), '') = ''
              AND COALESCE(TRIM(c.email_secondary), '') = ''
              AND COALESCE(TRIM(c.email_tertiary), '') = ''
              AND COALESCE(TRIM(c.representative_email), '') = ''
            ORDER BY c.id DESC
            """);

        // Contacts with no invoices
        List<Map<String, Object>> noInvoices = jdbcTemplate.queryForList("""
            SELECT c.id, COALESCE(c.name, c.contact_name, c.billing_name) AS name,
                   c.email_primary, c.status, c.tenant_type, c.created_at
            FROM beworking.contact_profiles c
            WHERE c.id NOT IN (
                SELECT DISTINCT f.idcliente FROM beworking.facturas f WHERE f.idcliente IS NOT NULL
            )
            ORDER BY c.id DESC
            """);

        Map<String, Object> response = new HashMap<>();
        response.put("noName", Map.of("count", noName.size(), "contacts", noName));
        response.put("noEmail", Map.of("count", noEmail.size(), "contacts", noEmail));
        response.put("noInvoices", Map.of("count", noInvoices.size(), "contacts", noInvoices));

        return ResponseEntity.ok(response);
    }

    @GetMapping("/vat/validate")
    public ResponseEntity<Map<String, Object>> validateVat(
            @RequestParam String vatNumber,
            @RequestParam(required = false) String countryHint,
            @RequestParam(required = false) Long contactId) {
        ViesVatService.VatValidationResult result = viesVatService.validate(vatNumber, countryHint);
        if (contactId != null) {
            try {
                contactProfileService.revalidateVat(contactId);
            } catch (Exception e) {
                logger.warn("Failed to persist VAT validation for contact {}: {}", contactId, e.getMessage());
            }
        }
        Map<String, Object> body = new HashMap<>();
        body.put("valid", result.valid());
        body.put("name", result.name());
        body.put("address", result.address());
        body.put("error", result.error());
        return ResponseEntity.ok(body);
    }

    private static final java.util.concurrent.atomic.AtomicBoolean RESEED_IN_PROGRESS =
        new java.util.concurrent.atomic.AtomicBoolean(false);

    @PostMapping("/vat/revalidate-all")
    public ResponseEntity<Map<String, Object>> revalidateAllVat(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        boolean isAdmin = authentication.getAuthorities().stream()
            .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
        if (!isAdmin) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        // Fire-and-forget: ~1972 contacts × 1s VIES throttle ≈ 33 min, far over
        // the ALB's idle timeout. Run in a background thread; results land in
        // the backend log. Lock prevents two reseeds racing each other.
        if (!RESEED_IN_PROGRESS.compareAndSet(false, true)) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                "status", "rejected",
                "message", "VIES reseed already in progress"
            ));
        }

        new Thread(() -> {
            long start = System.currentTimeMillis();
            logger.info("VIES bulk reseed starting (admin={})",
                authentication.getName());
            try {
                Map<String, Integer> stats = contactProfileService.revalidateAllStaleVat();
                long elapsedMin = (System.currentTimeMillis() - start) / 60_000;
                logger.info("VIES bulk reseed finished in {} min: {}", elapsedMin, stats);
            } catch (Exception e) {
                logger.error("VIES bulk reseed failed", e);
            } finally {
                RESEED_IN_PROGRESS.set(false);
            }
        }, "vies-bulk-reseed").start();

        return ResponseEntity.accepted().body(Map.of(
            "status", "started",
            "message", "VIES revalidation running in background. Check backend logs for progress and final counts.",
            "estimatedMinutes", 33
        ));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteContactProfile(@PathVariable Long id) {
        try {
            boolean deleted = contactProfileService.deleteContactProfile(id);
            if (deleted) {
                return ResponseEntity.noContent().build();
            } else {
                return ResponseEntity.notFound().build();
            }
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }
}
