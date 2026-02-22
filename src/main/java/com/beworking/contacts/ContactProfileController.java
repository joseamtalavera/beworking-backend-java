package com.beworking.contacts;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import com.beworking.auth.User;
import com.beworking.auth.UserRepository;

@RestController
@RequestMapping("/api/contact-profiles")
@CrossOrigin(origins = {"http://localhost:5173", "http://localhost:5174"})
public class ContactProfileController {

    private final ContactProfileService contactProfileService;
    private final UserRepository userRepository;
    private final JdbcTemplate jdbcTemplate;

    public ContactProfileController(ContactProfileService contactProfileService,
                                     UserRepository userRepository,
                                     JdbcTemplate jdbcTemplate) {
        this.contactProfileService = contactProfileService;
        this.userRepository = userRepository;
        this.jdbcTemplate = jdbcTemplate;
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
