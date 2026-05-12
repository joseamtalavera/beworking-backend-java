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
 * Stats endpoints for the admin Overview card. Returns counts based on real billing
 * activity (payment captured OR Stripe invoice sent), not on contact-profile flags
 * that get set prematurely during the signup funnel.
 */
@RestController
@RequestMapping("/api/subscriptions/stats")
public class SubscriptionStatsController {

    private final JdbcTemplate jdbcTemplate;

    public SubscriptionStatsController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Counts Virtual Office subscriptions whose underlying invoice has Stripe
     * activity (paid via card OR invoice sent), bucketed by the subscription's
     * creation date. Funnel-drops (Potencial contacts that never paid) are
     * excluded because they have no facturas row with stripe IDs.
     */
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
                SELECT COUNT(DISTINCT s.id)
                FROM beworking.subscriptions s
                WHERE LOWER(s.description) LIKE '%oficina%virtual%'
                  AND s.created_at::date BETWEEN ? AND ?
                  AND EXISTS (
                    SELECT 1 FROM beworking.facturas f
                    WHERE f.idcliente = s.contact_id
                      AND (f.stripepaymentintentid1 IS NOT NULL
                           OR f.stripeinvoiceid IS NOT NULL)
                  )
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
