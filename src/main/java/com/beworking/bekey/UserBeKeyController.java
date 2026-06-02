package com.beworking.bekey;

import com.beworking.auth.User;
import com.beworking.auth.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
    private final UserRepository userRepository;

    public UserBeKeyController(BeKeyAccessService accessService, UserRepository userRepository) {
        this.accessService = accessService;
        this.userRepository = userRepository;
    }

    /** JWT email -> User -> contact_profiles id (tenant_id). Null if not authenticated / no contact. */
    private Long resolveContactId(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) return null;
        User user = userRepository.findByEmail(authentication.getName()).orElse(null);
        return user != null ? user.getTenantId() : null;
    }

    @GetMapping("/me")
    public ResponseEntity<?> me(Authentication authentication) {
        Long contactId = resolveContactId(authentication);
        if (contactId == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        List<BeKeyDevice> devices = accessService.listAccessibleDevices(contactId);
        String pin = accessService.getRevealedPin(contactId);   // keypad fallback; may be null
        Map<String, Object> body = new HashMap<>();
        body.put("devices", devices);
        body.put("pin", pin);
        return ResponseEntity.ok(body);
    }

    @PostMapping("/open/{deviceId}")
    public ResponseEntity<?> open(@PathVariable Long deviceId, Authentication authentication) {
        Long contactId = resolveContactId(authentication);
        if (contactId == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        try {
            BeKeyDevice device = accessService.openDoor(contactId, deviceId);
            return ResponseEntity.ok(Map.of("opened", true, "deviceId", device.getId(), "name", device.getName()));
        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "No access to this door"));
        }
    }
}
