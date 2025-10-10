package com.beworking.bookings;

import com.beworking.auth.User;
import com.beworking.auth.UserRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/bloqueos")
public class BloqueoController {

    private final BloqueoService bloqueoService;
    private final UserRepository userRepository;

    public BloqueoController(BloqueoService bloqueoService, UserRepository userRepository) {
        this.bloqueoService = bloqueoService;
        this.userRepository = userRepository;
    }

    @GetMapping
    public ResponseEntity<List<BloqueoResponse>> listBloqueos(
        Authentication authentication,
        @RequestParam(value = "from", required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
        @RequestParam(value = "to", required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
        @RequestParam(value = "centerId", required = false) Long centerId,
        @RequestParam(value = "contactId", required = false) Long contactId,
        @RequestParam(value = "productId", required = false) Long productId,
        @RequestParam(value = "tenantId", required = false) Long tenantIdParam
    ) {
        if (from == null && to == null) {
            LocalDate today = LocalDate.now();
            from = today.minusMonths(1);
            to = today.plusMonths(3);
        } else if (from != null && to == null) {
            to = from.plusMonths(1);
        } else if (to != null && from == null) {
            from = to.minusMonths(1);
        }

        if (authentication == null || !authentication.isAuthenticated()) {
            return ResponseEntity.status(401).build();
        }

        Optional<User> userOpt = userRepository.findByEmail(authentication.getName());
        if (userOpt.isEmpty()) {
            return ResponseEntity.status(401).build();
        }

        User user = userOpt.get();
        Long effectiveTenantId;
        Long effectiveContactId = contactId;

        if (user.getRole() == User.Role.ADMIN) {
            effectiveTenantId = tenantIdParam;
        } else {
            effectiveTenantId = user.getTenantId();
            if (effectiveTenantId == null) {
                return ResponseEntity.status(403).build();
            }
            effectiveContactId = effectiveTenantId;
        }

        List<BloqueoResponse> bloqueos = bloqueoService.getBloqueos(
            from,
            to,
            centerId,
            effectiveContactId,
            productId,
            effectiveTenantId
        );

        return ResponseEntity.ok(bloqueos);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteBloqueo(Authentication authentication, @PathVariable Long id) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return ResponseEntity.status(401).build();
        }

        Optional<User> userOpt = userRepository.findByEmail(authentication.getName());
        if (userOpt.isEmpty()) {
            return ResponseEntity.status(401).build();
        }

        User user = userOpt.get();
        if (user.getRole() != User.Role.ADMIN) {
            return ResponseEntity.status(403).build();
        }

        bloqueoService.deleteBloqueo(id);
        return ResponseEntity.noContent().build();
    }
}
