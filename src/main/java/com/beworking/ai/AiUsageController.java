package com.beworking.ai;

import com.beworking.auth.User;
import com.beworking.auth.UserRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/ai")
public class AiUsageController {

    private final AiUsageService aiUsageService;
    private final UserRepository userRepository;

    public AiUsageController(AiUsageService aiUsageService, UserRepository userRepository) {
        this.aiUsageService = aiUsageService;
        this.userRepository = userRepository;
    }

    @GetMapping("/usage")
    public ResponseEntity<Map<String, Object>> getUsage(@AuthenticationPrincipal UserDetails principal) {
        Long tenantId = getTenantId(principal);
        if (tenantId == null) return ResponseEntity.badRequest().build();
        return ResponseEntity.ok(aiUsageService.getUsageSummary(tenantId));
    }

    @PostMapping("/query")
    public ResponseEntity<Map<String, Object>> recordQuery(@AuthenticationPrincipal UserDetails principal) {
        Long tenantId = getTenantId(principal);
        if (tenantId == null) return ResponseEntity.badRequest().build();

        if (!aiUsageService.canQuery(tenantId)) {
            return ResponseEntity.status(429).body(Map.of(
                "error", "AI query limit exceeded",
                "limit", aiUsageService.getLimit(tenantId),
                "used", aiUsageService.getUsed(tenantId),
                "upgrade", true
            ));
        }

        boolean overLimit = aiUsageService.isOverLimit(tenantId);
        aiUsageService.recordQuery(tenantId);

        var summary = aiUsageService.getUsageSummary(tenantId);
        if (overLimit) {
            summary = new java.util.HashMap<>(summary);
            summary.put("warning", "You have exceeded your plan limit. Please upgrade for continued access.");
        }
        return ResponseEntity.ok(summary);
    }

    private Long getTenantId(UserDetails principal) {
        if (principal == null) return null;
        return userRepository.findByEmail(principal.getUsername())
            .map(User::getTenantId)
            .orElse(null);
    }
}
