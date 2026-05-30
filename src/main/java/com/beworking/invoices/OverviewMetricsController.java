package com.beworking.invoices;

import java.time.LocalDate;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin Overview metrics — single source of truth for the dashboard's
 * Overview tab. All math happens server-side. The frontend just renders.
 *
 * <p>Protected by SecurityConfig: /api/admin/** requires ROLE_ADMIN.
 */
@RestController
@RequestMapping("/api/admin/overview")
public class OverviewMetricsController {

    private final OverviewMetricsService service;

    public OverviewMetricsController(OverviewMetricsService service) {
        this.service = service;
    }

    @GetMapping("/metrics")
    public ResponseEntity<Map<String, Object>> metrics(
            @RequestParam(value = "year", required = false) Integer year) {
        int y = year != null ? year : LocalDate.now().getYear();
        return ResponseEntity.ok(service.getMetrics(y));
    }
}
