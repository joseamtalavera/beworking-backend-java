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
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
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
        User user = resolveUser(authentication);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        String contactEmail = null;
        if (user.getRole() == User.Role.ADMIN) {
            // Admin may scope to any contact's email, or see all when no filter is given.
            if (StringUtils.hasText(contactEmailParam)) {
                contactEmail = contactEmailParam.trim();
            }
        } else {
            // Non-admin: ALWAYS scoped to own email; client-supplied contactEmail ignored.
            contactEmail = resolveOwnEmail(user);
            if (contactEmail == null) {
                return ResponseEntity.ok(List.of());
            }
        }

        return ResponseEntity.ok(service.listRecentDocuments(null, contactEmail));
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
        User user = resolveUser(authentication);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (user.getRole() != User.Role.ADMIN) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

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
    public ResponseEntity<MailroomDocumentResponse> markNotified(
            @PathVariable("id") UUID documentId,
            Authentication authentication) {
        User user = resolveUser(authentication);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (user.getRole() != User.Role.ADMIN) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        MailroomDocumentResponse updated = service.markDocumentNotified(documentId);
        return ResponseEntity.ok(updated);
    }

    @PostMapping("/{id}/view")
    public ResponseEntity<MailroomDocumentResponse> markViewed(
            @PathVariable("id") UUID documentId,
            Authentication authentication) {
        User user = resolveUser(authentication);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (!canAccessDocument(user, documentId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        MailroomDocumentResponse updated = service.markDocumentViewed(documentId);
        return ResponseEntity.ok(updated);
    }

    @PostMapping("/verify-pickup")
    public ResponseEntity<MailroomDocumentResponse> verifyPickup(
            @RequestParam("code") String code,
            Authentication authentication) {
        User user = resolveUser(authentication);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (user.getRole() != User.Role.ADMIN) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        MailroomDocumentResponse updated = service.verifyPickup(code);
        return ResponseEntity.ok(updated);
    }

    @PostMapping("/{id}/pickup")
    public ResponseEntity<MailroomDocumentResponse> markPickedUp(
            @PathVariable("id") UUID documentId,
            Authentication authentication) {
        User user = resolveUser(authentication);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (user.getRole() != User.Role.ADMIN) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        MailroomDocumentResponse updated = service.verifyPickupById(documentId);
        return ResponseEntity.ok(updated);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteDocument(
            @PathVariable("id") UUID documentId,
            Authentication authentication) {
        User user = resolveUser(authentication);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (user.getRole() != User.Role.ADMIN) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        service.deleteDocument(documentId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}/download")
    public ResponseEntity<Resource> downloadDocument(
            @PathVariable("id") UUID documentId,
            Authentication authentication) {
        User user = resolveUser(authentication);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (!canAccessDocument(user, documentId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
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

    /**
     * A non-UUID path variable (e.g. /documents/not-a-uuid/download) fails type
     * conversion and would otherwise surface as a 500. A malformed id is a client
     * error, so return 400 instead.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Void> handleBadPathVariable(MethodArgumentTypeMismatchException ex) {
        return ResponseEntity.badRequest().build();
    }

    /** Loads the authenticated user from the verified token, or null if not authenticated/known. */
    private User resolveUser(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return null;
        }
        return userRepository.findByEmail(authentication.getName()).orElse(null);
    }

    /** The caller's OWN contact email, derived from their identity (never from request input). */
    private String resolveOwnEmail(User user) {
        if (user.getTenantId() != null) {
            return user.getEmail();
        }
        final String[] emailHolder = {null};
        contactService.findContactByEmail(user.getEmail()).ifPresent(contact -> {
            emailHolder[0] = firstNonBlank(
                contact.getEmailPrimary(),
                contact.getEmailSecondary(),
                contact.getEmailTertiary(),
                contact.getRepresentativeEmail()
            );
        });
        return emailHolder[0];
    }

    /** Admins may touch any document; everyone else only their own. */
    private boolean canAccessDocument(User user, UUID documentId) {
        if (user.getRole() == User.Role.ADMIN) {
            return true;
        }
        String own = resolveOwnEmail(user);
        return own != null && service.isDocumentOwnedByEmail(documentId, own);
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
