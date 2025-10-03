package com.beworking.contacts;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/contact-profiles")
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
        @RequestParam(value = "plan", required = false) String plan
    ) {
        ContactProfilesPageResponse profiles = contactProfileService.getContactProfiles(page, size, search, status, plan);
        return ResponseEntity.ok(profiles);
    }
}
