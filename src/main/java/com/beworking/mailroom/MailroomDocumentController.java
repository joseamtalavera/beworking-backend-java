package com.beworking.mailroom;

import com.beworking.auth.User;
import com.beworking.auth.UserRepository;
import com.beworking.contacts.ContactProfileService;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/mailroom/documents")
public class MailroomDocumentController {

    private final MailroomDocumentService service;
    private final UserRepository userRepository;
    private final ContactProfileService contactService;

    public MailroomDocumentController(MailroomDocumentService service, UserRepository userRepository, ContactProfileService contactService) {
        this.service = service;
        this.userRepository = userRepository;
        this.contactService = contactService;
    }

    @GetMapping
    public ResponseEntity<List<MailroomDocumentResponse>> listDocuments(
            Authentication authentication,
            @RequestParam(value = "contactEmail", required = false) String contactEmailParam) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        String userEmail = authentication.getName();
        User user = userRepository.findByEmail(userEmail).orElse(null);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        UUID tenantFilter = null;
        String contactEmail = null;

        // If a contactEmail filter is explicitly provided, use it
        if (StringUtils.hasText(contactEmailParam)) {
            contactEmail = contactEmailParam.trim();
        } else if (user.getRole() == User.Role.USER) {
            if (user.getTenantId() != null) {
                contactEmail = user.getEmail();
            } else {
                final String[] emailHolder = {null};
                contactService.findContactByEmail(user.getEmail()).ifPresent(contact -> {
                    emailHolder[0] = firstNonBlank(
                        contact.getEmailPrimary(),
                        contact.getEmailSecondary(),
                        contact.getEmailTertiary(),
                        contact.getRepresentativeEmail()
                    );
                });
                contactEmail = emailHolder[0];
            }
        }

        return ResponseEntity.ok(service.listRecentDocuments(tenantFilter, contactEmail));
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<MailroomDocumentResponse> uploadDocument(
            @RequestPart("file") MultipartFile file,
            @RequestParam(value = "title", required = false) String title,
            @RequestParam(value = "receivedAt", required = false) String receivedAt,
            @RequestParam(value = "tenantId", required = false) String tenantId,
            @RequestParam(value = "pages", required = false) Integer pages,
            @RequestParam(value = "avatarColor", required = false) String avatarColor,
            @RequestParam(value = "contactEmail", required = false) String contactEmail,
            @RequestParam(value = "autoNotify", required = false, defaultValue = "false") boolean autoNotify,
            @RequestParam(value = "documentType", required = false) String documentType,
            Authentication authentication
    ) {
        MailroomDocumentResponse created = service.createDocument(
                file,
                title,
                resolveSender(authentication),
                parseInstant(receivedAt),
                tenantId,
                pages,
                StringUtils.hasText(avatarColor) ? avatarColor : null,
                contactEmail,
                documentType
        );

        if (autoNotify && created.id() != null) {
            try {
                created = service.markDocumentNotified(created.id());
            } catch (Exception e) {
                // Don't fail the upload if notification fails
                System.err.println("Auto-notify failed for document " + created.id() + ": " + e.getMessage());
            }
        }

        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PostMapping("/{id}/notify")
    public ResponseEntity<MailroomDocumentResponse> markNotified(@PathVariable("id") UUID documentId) {
        MailroomDocumentResponse updated = service.markDocumentNotified(documentId);
        return ResponseEntity.ok(updated);
    }

    @PostMapping("/{id}/view")
    public ResponseEntity<MailroomDocumentResponse> markViewed(@PathVariable("id") UUID documentId) {
        MailroomDocumentResponse updated = service.markDocumentViewed(documentId);
        return ResponseEntity.ok(updated);
    }

    @PostMapping("/verify-pickup")
    public ResponseEntity<MailroomDocumentResponse> verifyPickup(@RequestParam("code") String code) {
        MailroomDocumentResponse updated = service.verifyPickup(code);
        return ResponseEntity.ok(updated);
    }

    @PostMapping("/{id}/pickup")
    public ResponseEntity<MailroomDocumentResponse> markPickedUp(@PathVariable("id") UUID documentId) {
        MailroomDocumentResponse updated = service.verifyPickupById(documentId);
        return ResponseEntity.ok(updated);
    }

    @GetMapping("/{id}/download")
    public ResponseEntity<Resource> downloadDocument(@PathVariable("id") UUID documentId) {
        MailroomDocumentDownload download = service.getDocumentDownload(documentId);
        MediaType mediaType = MediaType.parseMediaType(download.contentType());
        ContentDisposition contentDisposition = ContentDisposition.inline()
                .filename(download.originalFileName(), StandardCharsets.UTF_8)
                .build();

        return ResponseEntity.ok()
                .contentType(mediaType)
                .header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition.toString())
                .body(download.resource());
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private Instant parseInstant(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    private String resolveSender(Authentication authentication) {
        if (authentication == null) {
            return "System";
        }
        if (StringUtils.hasText(authentication.getName())) {
            return authentication.getName();
        }
        Object principal = authentication.getPrincipal();
        return principal != null ? principal.toString() : "System";
    }
}
