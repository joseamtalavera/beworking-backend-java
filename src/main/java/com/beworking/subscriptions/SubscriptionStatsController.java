package com.beworking.subscriptions;

import java.time.LocalDate;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Stats endpoints for the admin Overview card. Counts active Virtual Office
 * contacts, bucketed by when their status last flipped to Activo
 * (status_changed_at). Captures every conversion path:
 *   - self-signup paid (InvoiceService stamps status_changed_at on invoice)
 *   - admin manually flips Potencial → Activo (ContactProfileService.updateContactProfile)
 *   - lead converted to a new contact_profile (ContactProfileService.createContactProfile)
 *   - admin creates a subscription that posts an invoice
 * Funnel-drops stay Potencial until payment, so they're naturally excluded.
 */
@RestController
@RequestMapping("/api/subscriptions/stats")
public class SubscriptionStatsController {

    private final JdbcTemplate jdbcTemplate;

    public SubscriptionStatsController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @GetMapping("/virtual-offices")
    public ResponseEntity<?> virtualOffices(Authentication authentication,
                                            @RequestParam String startDate,
                                            @RequestParam String endDate) {
        if (!isAdmin(authentication)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        try {
            LocalDate start = LocalDate.parse(startDate);
            LocalDate end = LocalDate.parse(endDate);
            Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM beworking.contact_profiles
                WHERE tenant_type = 'Usuario Virtual'
                  AND status = 'Activo'
                  AND status_changed_at::date BETWEEN ? AND ?
                """, Integer.class, start, end);
            return ResponseEntity.ok(Map.of("count", count != null ? count : 0));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    private boolean isAdmin(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) return false;
        return authentication.getAuthorities().stream()
            .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
    }
}
