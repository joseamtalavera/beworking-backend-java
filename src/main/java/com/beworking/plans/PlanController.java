package com.beworking.plans;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
public class PlanController {
    private final PlanRepository planRepository;

    public PlanController(PlanRepository planRepository) {
        this.planRepository = planRepository;
    }

    /**
     * Public endpoint — returns active plans with prices and features.
     * Used by landing pages to display pricing cards.
     */
    @GetMapping("/api/public/plans")
    public ResponseEntity<List<Map<String, Object>>> getActivePlans() {
        var plans = planRepository.findByActiveTrueOrderBySortOrder();
        var result = plans.stream().map(p -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", p.getPlanKey());
            m.put("name", p.getName());
            m.put("price", p.getPrice());
            m.put("currency", p.getCurrency());
            m.put("features", p.getFeatures() != null
                    ? Arrays.asList(p.getFeatures().split(","))
                    : List.of());
            m.put("popular", p.isPopular());
            return m;
        }).toList();
        return ResponseEntity.ok(result);
    }

    /**
     * Admin endpoint — update plan price, features, etc.
     */
    @PutMapping("/api/plans/{id}")
    public ResponseEntity<?> updatePlan(@PathVariable Integer id,
                                         @RequestBody Map<String, Object> body,
                                         Authentication auth) {
        if (auth == null || !auth.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"))) {
            return ResponseEntity.status(403).body(Map.of("error", "Admin only"));
        }

        var planOpt = planRepository.findById(id);
        if (planOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        Plan plan = planOpt.get();
        if (body.containsKey("name")) plan.setName((String) body.get("name"));
        if (body.containsKey("price")) plan.setPrice(new java.math.BigDecimal(body.get("price").toString()));
        if (body.containsKey("features")) plan.setFeatures((String) body.get("features"));
        if (body.containsKey("popular")) plan.setPopular((Boolean) body.get("popular"));
        if (body.containsKey("active")) plan.setActive((Boolean) body.get("active"));
        if (body.containsKey("sortOrder")) plan.setSortOrder((Integer) body.get("sortOrder"));
        plan.setUpdatedAt(LocalDateTime.now());
        planRepository.save(plan);

        return ResponseEntity.ok(Map.of("message", "Plan updated"));
    }

    /**
     * Admin endpoint — list all plans (including inactive).
     */
    @GetMapping("/api/plans")
    public ResponseEntity<?> getAllPlans(Authentication auth) {
        if (auth == null || !auth.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"))) {
            return ResponseEntity.status(403).body(Map.of("error", "Admin only"));
        }
        return ResponseEntity.ok(planRepository.findAll());
    }
}
