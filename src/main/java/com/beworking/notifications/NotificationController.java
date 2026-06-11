package com.beworking.notifications;

import com.beworking.auth.User;
import com.beworking.auth.UserRepository;
import com.beworking.contacts.ContactProfileService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

    private final NotificationService service;
    private final UserRepository userRepository;
    private final ContactProfileService contactService;

    public NotificationController(NotificationService service, UserRepository userRepository,
                                  ContactProfileService contactService) {
        this.service = service;
        this.userRepository = userRepository;
        this.contactService = contactService;
    }

    @GetMapping
    public ResponseEntity<List<NotificationResponse>> list(
            Authentication authentication,
            @RequestParam(value = "contactEmail", required = false) String contactEmailParam) {
        User user = resolveUser(authentication);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (user.getRole() == User.Role.ADMIN) {
            if (StringUtils.hasText(contactEmailParam)) {
                return ResponseEntity.ok(service.listByContactEmail(contactEmailParam.trim()));
            }
            return ResponseEntity.ok(service.listAll());
        }
        // Non-admin: always scoped to own email; client input ignored.
        String own = resolveOwnEmail(user);
        if (own == null) {
            return ResponseEntity.ok(List.of());
        }
        return ResponseEntity.ok(service.listByContactEmail(own));
    }

    @PostMapping
    public ResponseEntity<NotificationResponse> create(
            Authentication authentication,
            @RequestBody Map<String, Object> payload) {
        User user = resolveUser(authentication);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (user.getRole() != User.Role.ADMIN) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        String contactEmail = asString(payload.get("contactEmail"));
        String subject = asString(payload.get("subject"));
        String body = asString(payload.get("body"));
        UUID tenantId = parseUuid(asString(payload.get("tenantId")));
        NotificationResponse created = service.create(contactEmail, subject, body, tenantId, user.getEmail());
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PostMapping("/{id}/read")
    public ResponseEntity<NotificationResponse> markRead(
            @PathVariable("id") UUID id, Authentication authentication) {
        User user = resolveUser(authentication);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (!canAccess(user, id)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        return ResponseEntity.ok(service.markRead(id));
    }

    @PostMapping("/{id}/acknowledge")
    public ResponseEntity<NotificationResponse> acknowledge(
            @PathVariable("id") UUID id, Authentication authentication) {
        User user = resolveUser(authentication);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (!canAccess(user, id)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        return ResponseEntity.ok(service.acknowledge(id));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(
            @PathVariable("id") UUID id, Authentication authentication) {
        User user = resolveUser(authentication);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (user.getRole() != User.Role.ADMIN) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Void> handleBadPathVariable(MethodArgumentTypeMismatchException ex) {
        return ResponseEntity.badRequest().build();
    }

    private User resolveUser(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return null;
        }
        return userRepository.findByEmail(authentication.getName()).orElse(null);
    }

    private String resolveOwnEmail(User user) {
        if (user.getTenantId() != null) {
            return user.getEmail();
        }
        final String[] emailHolder = {null};
        contactService.findContactByEmail(user.getEmail()).ifPresent(contact ->
            emailHolder[0] = firstNonBlank(
                contact.getEmailPrimary(),
                contact.getEmailSecondary(),
                contact.getEmailTertiary(),
                contact.getRepresentativeEmail()));
        return emailHolder[0];
    }

    private boolean canAccess(User user, UUID id) {
        if (user.getRole() == User.Role.ADMIN) {
            return true;
        }
        String own = resolveOwnEmail(user);
        return own != null && service.isOwnedByEmail(id, own);
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

    private String asString(Object o) {
        return o != null ? o.toString() : null;
    }

    private UUID parseUuid(String s) {
        if (!StringUtils.hasText(s)) {
            return null;
        }
        try {
            return UUID.fromString(s.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
