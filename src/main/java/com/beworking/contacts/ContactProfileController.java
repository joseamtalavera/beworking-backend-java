package com.beworking.contacts;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/contact-profiles")
@CrossOrigin(origins = {"http://localhost:5173", "http://localhost:5174"})
public class ContactProfileController {

    private final ContactProfileService contactProfileService;

    public ContactProfileController(ContactProfileService contactProfileService) {
        this.contactProfileService = contactProfileService;
    }

    @GetMapping
    public ResponseEntity<ContactProfilesPageResponse> getAllContactProfiles(
        @RequestParam(value = "page", defaultValue = "0") int page,
        @RequestParam(value = "size", defaultValue = "25") int size,
        @RequestParam(value = "search", required = false) String search,
        @RequestParam(value = "status", required = false) String status,
        @RequestParam(value = "plan", required = false) String plan,
        @RequestParam(value = "tenantType", required = false) String tenantType,
        @RequestParam(value = "email", required = false) String email
    ) {
        ContactProfilesPageResponse profiles = contactProfileService.getContactProfiles(page, size, search, status, plan, tenantType, email);
        return ResponseEntity.ok(profiles);
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
