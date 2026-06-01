package com.beworking.bekey;

import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.List;
import java.time.OffsetDateTime;
import java.util.Map;

/**
 * Admin BeKey endpoints (ROLE_ADMIN only) behind the dashboard's BeKey tab.
 *
 *   GET    /api/admin/bekey/devices       — list access devices (doors)
 *   GET    /api/admin/bekey/access        — list grants (optionally ?contactId=)
 *   GET    /api/admin/bekey/events        — recent access events
 *   POST   /api/admin/bekey/access        — manual grant   (Step 2)
 *   DELETE /api/admin/bekey/access/{id}   — manual revoke  (Step 2)
 */
@RestController
@RequestMapping("/api/admin/bekey")
public class AdminBeKeyController {

    private final BeKeyAccessService accessService;
    private final BeKeyAccessRepository accessRepository;
    private final BeKeyDeviceRepository deviceRepository;
    private final BeKeyEventRepository eventRepository;

    public AdminBeKeyController(BeKeyAccessService accessService,
                                BeKeyAccessRepository accessRepository,
                                BeKeyDeviceRepository deviceRepository,
                                BeKeyEventRepository eventRepository) {
        this.accessService = accessService;
        this.accessRepository = accessRepository;
        this.deviceRepository = deviceRepository;
        this.eventRepository = eventRepository;
    }

    private boolean isAdmin(Authentication authentication) {
        return authentication != null
            && authentication.isAuthenticated()
            && authentication.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
    }

    @GetMapping("/devices")
    public ResponseEntity<List<BeKeyDevice>> listDevices(Authentication authentication) {
        if (!isAdmin(authentication)) return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        return ResponseEntity.ok(deviceRepository.findAll());
    }

    @GetMapping("/access")
    public ResponseEntity<List<BeKeyAccess>> listAccess(
            @RequestParam(required = false) Long contactId,
            Authentication authentication) {
        if (!isAdmin(authentication)) return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        List<BeKeyAccess> rows = (contactId != null)
                ? accessService.listForContact(contactId)
                : accessRepository.findAll();
        return ResponseEntity.ok(rows);
    }

    @PostMapping("/access")
    public ResponseEntity<?> grant(@RequestBody GrantRequest req, Authentication authentication) {
        if (!isAdmin(authentication)) return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        if (req.contactId() == null || req.memberGroupId() == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "contactId and memberGroupId are required"));
        }
        BeKeyAccess access = accessService.grant(
                req.contactId(), req.memberGroupId(),
                BeKeyAccess.Source.manual, null, req.expiresAt());
        return ResponseEntity.status(HttpStatus.CREATED).body(access);
    }

    @DeleteMapping("/access/{id}")
    public ResponseEntity<BeKeyAccess> revoke(
            @PathVariable Long id,
            @RequestParam(required = false) String reason,
            Authentication authentication) {
        if (!isAdmin(authentication)) return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        String why = (reason != null && !reason.isBlank()) ? reason : "admin manual revoke";
        return ResponseEntity.ok(accessService.revoke(id, why));
    }

    @GetMapping("/events")
    public ResponseEntity<List<BeKeyEvent>> listEvents(Authentication authentication) {
        if (!isAdmin(authentication)) return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        return ResponseEntity.ok(eventRepository.findAll(Sort.by(Sort.Direction.DESC, "occurredAt")));
    }

    /** Manual-grant request body. expiresAt may be null for unbounded access. */
    public record GrantRequest(Long contactId, Long memberGroupId, OffsetDateTime expiresAt) {}
}
