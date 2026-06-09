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
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import com.beworking.contacts.ContactProfile;
import com.beworking.contacts.ContactProfileRepository;

import java.util.HashMap;
import java.util.List;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.stream.Collectors;

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
    private final BeKeyMemberGroupRepository memberGroupRepository;
    private final ContactProfileRepository contactRepository;
    private final BeKeyAnnouncementService announcementService;

    public AdminBeKeyController(BeKeyAccessService accessService,
                                BeKeyAccessRepository accessRepository,
                                BeKeyDeviceRepository deviceRepository,
                                BeKeyEventRepository eventRepository,
                                BeKeyMemberGroupRepository memberGroupRepository,
                                ContactProfileRepository contactRepository,
                                BeKeyAnnouncementService announcementService) {
        this.accessService = accessService;
        this.accessRepository = accessRepository;
        this.deviceRepository = deviceRepository;
        this.eventRepository = eventRepository;
        this.memberGroupRepository = memberGroupRepository;
        this.contactRepository = contactRepository;
        this.announcementService = announcementService;
    }

    // Resolve each grant's contact name in one batch query so the admin list can
    // show "Jose Talavera" instead of "Contacto 91009".
    private void enrichContactNames(List<BeKeyAccess> rows) {
        List<Long> ids = rows.stream().map(BeKeyAccess::getContactId).distinct().collect(Collectors.toList());
        if (ids.isEmpty()) return;
        Map<Long, String> names = new HashMap<>();
        for (ContactProfile c : contactRepository.findAllById(ids)) {
            names.put(c.getId(), c.getName());
        }
        rows.forEach(r -> r.setContactName(names.get(r.getContactId())));
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

    @GetMapping("/member-groups")
    public ResponseEntity<List<BeKeyMemberGroup>> listMemberGroups(Authentication authentication) {
        if (!isAdmin(authentication)) return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        return ResponseEntity.ok(memberGroupRepository.findAll());
    }

    @GetMapping("/access")
    public ResponseEntity<List<BeKeyAccess>> listAccess(
            @RequestParam(required = false) Long contactId,
            Authentication authentication) {
        if (!isAdmin(authentication)) return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        List<BeKeyAccess> rows = (contactId != null)
                ? accessService.listForContact(contactId)
                : accessRepository.findAll();
        enrichContactNames(rows);
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
                BeKeyAccess.Source.manual, null, req.startsAt(), req.expiresAt());
        return ResponseEntity.status(HttpStatus.CREATED).body(access);
    }

    @PatchMapping("/access/{id}")
    public ResponseEntity<?> updateWindow(@PathVariable Long id, @RequestBody UpdateRequest req, Authentication authentication) {
        if (!isAdmin(authentication)) return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        try {
            return ResponseEntity.ok(accessService.updateManualWindow(id, req.startsAt(), req.expiresAt()));
        } catch (IllegalStateException ex) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", ex.getMessage()));
        }
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

    /**
     * One-shot BeKey launch announcement (#255). Defaults to dryRun=true: nothing
     * is sent, you just get the recipient count + breakdown. Pass dryRun=false to
     * actually send (idempotent — each holder is emailed at most once, ever).
     */
    @PostMapping("/announcement")
    public ResponseEntity<?> announce(@RequestParam(name = "dryRun", defaultValue = "true") boolean dryRun,
                                      Authentication authentication) {
        if (!isAdmin(authentication)) return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        return ResponseEntity.ok(announcementService.run(dryRun));
    }

    /** Manual-grant request body. startsAt/expiresAt may be null (now / unbounded). */
    public record GrantRequest(Long contactId, Long memberGroupId, OffsetDateTime startsAt, OffsetDateTime expiresAt) {}

    /** Edit-window request body for a manual grant. */
    public record UpdateRequest(OffsetDateTime startsAt, OffsetDateTime expiresAt) {}
}
