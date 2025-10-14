package com.beworking.contacts;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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

    public ContactProfileController(ContactProfileService contactProfileService, UserRepository userRepository) {
        this.contactProfileService = contactProfileService;
        this.userRepository = userRepository;
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
        @RequestParam(value = "email", required = false) String email
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
                    user.getTenantId(), page, size, search, status, plan, tenantType, email
                );
                return ResponseEntity.ok(profiles);
            } else {
                // User has no tenantId, try to find their contact by email
                ContactProfilesPageResponse profiles = contactProfileService.getContactProfilesByEmail(
                    user.getEmail(), page, size, search, status, plan, tenantType, email
                );
                return ResponseEntity.ok(profiles);
            }
        } else {
            // Admins can see all contacts
            ContactProfilesPageResponse profiles = contactProfileService.getContactProfiles(page, size, search, status, plan, tenantType, email);
            return ResponseEntity.ok(profiles);
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
