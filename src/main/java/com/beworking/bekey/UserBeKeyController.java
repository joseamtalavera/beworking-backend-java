package com.beworking.bekey;

import com.beworking.auth.User;
import com.beworking.auth.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.HashMap;
import java.util.Map;

/**
 * User-facing BeKey endpoints — authenticated user, scoped to their OWN contact.
 *
 *   GET  /api/bekey/me           — doors the caller can open (the buttons)
 *   POST /api/bekey/open/{id}    — open one of those doors
 */
@RestController
@RequestMapping("/api/bekey")
public class UserBeKeyController {

    private final BeKeyAccessService accessService;
    private final BeKeyShareService shareService;
    private final UserRepository userRepository;

    public UserBeKeyController(BeKeyAccessService accessService, BeKeyShareService shareService, UserRepository userRepository) {
        this.accessService = accessService;
        this.shareService = shareService;
        this.userRepository = userRepository;
    }

    /** JWT email -> User -> contact_profiles id (tenant_id). Null if not authenticated / no contact. */
    private Long resolveContactId(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) return null;
        User user = userRepository.findByEmail(authentication.getName()).orElse(null);
        return user != null ? user.getTenantId() : null;
    }

    private boolean isAdmin(Authentication authentication) {
        return authentication != null
            && authentication.isAuthenticated()
            && authentication.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
    }

    @GetMapping("/me")
    public ResponseEntity<?> me(Authentication authentication) {
        boolean admin = isAdmin(authentication);
        Long contactId = resolveContactId(authentication);
        if (contactId == null && !admin) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        // Admins hold a master key: every door, independent of any booking or grant.
        List<BeKeyDevice> devices = admin
                ? accessService.listAllDevices()
                : accessService.listAccessibleDevices(contactId);
        String pin = contactId != null ? accessService.getRevealedPin(contactId) : null;  // keypad fallback; may be null
        Map<String, Object> body = new HashMap<>();
        body.put("devices", devices);
        body.put("pin", pin);
        body.put("admin", admin);
        return ResponseEntity.ok(body);
    }

    @PostMapping("/open/{deviceId}")
    public ResponseEntity<?> open(@PathVariable Long deviceId, Authentication authentication) {
        boolean admin = isAdmin(authentication);
        Long contactId = resolveContactId(authentication);
        if (contactId == null && !admin) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        try {
            BeKeyDevice device = accessService.openDoor(contactId, deviceId, admin);
            return ResponseEntity.ok(Map.of("opened", true, "deviceId", device.getId(), "name", device.getName()));
        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "No access to this door"));
        }
    }

    /** Shares the caller created (active, non-revoked). */
    @GetMapping("/shares")
    public ResponseEntity<?> listShares(Authentication authentication) {
        Long contactId = resolveContactId(authentication);
        if (contactId == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        return ResponseEntity.ok(shareService.listForSharer(contactId));
    }

    /** Share the caller's access with a guest for a bounded window (#243). */
    @PostMapping("/shares")
    public ResponseEntity<?> createShare(@RequestBody ShareRequest req, Authentication authentication) {
        Long contactId = resolveContactId(authentication);
        if (contactId == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        try {
            BeKeyShare share = shareService.createShare(
                    contactId, req.guestName(), req.guestEmail(), req.startsAt(), req.endsAt());
            return ResponseEntity.status(HttpStatus.CREATED).body(share);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", e.getMessage()));
        }
    }

    /** Revoke a share the caller created. */
    @DeleteMapping("/shares/{id}")
    public ResponseEntity<?> revokeShare(@PathVariable Long id, Authentication authentication) {
        Long contactId = resolveContactId(authentication);
        if (contactId == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        try {
            shareService.revoke(id, contactId);
            return ResponseEntity.ok(Map.of("revoked", true));
        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Not your share"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
        }
    }

    /** Create-share request body. startsAt null = now; endsAt required. */
    public record ShareRequest(String guestName, String guestEmail, OffsetDateTime startsAt, OffsetDateTime endsAt) {}
}
