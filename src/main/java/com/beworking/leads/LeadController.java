
package com.beworking.leads;

import jakarta.validation.Valid;  // Import for validation annotations
import org.springframework.http.HttpStatus; // Import for HttpStatus which is used to define HTTP status codes
import org.springframework.http.ResponseEntity; // Import for ResponseEntity which is used to return HTTP responses
import org.springframework.web.bind.annotation.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.ApplicationEventPublisher;
import java.util.HashMap; // Import for HashMap to store leads in memory
import java.util.Map; // Import for Map interface which is used to define the structure of the response
import com.beworking.leads.SanitizationUtils;

/**
 * REST controller that accepts lead submissions and persists them.
 *
 * <p>Behavior summary:
 * - Incoming payload is validated by Spring's bean validation (the request DTO is annotated with
 *   validation constraints). Note: validation is triggered before this method executes, so the
 *   request must already conform to constraints (for example, a trimmed and well-formed email).
 * - This controller performs application-level sanitization and normalization on fields before
 *   persisting: it strips HTML from the name via {@link SanitizationUtils#sanitizeText(String)},
 *   trims the email, and normalizes the phone via {@link SanitizationUtils#sanitizePhone(String)}.
 * - After saving the lead, a {@link LeadCreatedEvent} is published so background listeners (email,
 *   HubSpot sync, etc.) can react asynchronously.
 *
 * <p>Testing notes:
 * - Tests that exercise this controller should send values that pass bean validation (trimmed
 *   email, valid phone). The controller's sanitization is applied before persisting, and tests
 *   typically assert on the saved entity or the repository interaction to verify normalization.
 */
@RestController
@RequestMapping("/api/leads")
public class LeadController {

    private final LeadRepository leadRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final TurnstileService turnstileService;
    private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(LeadController.class);
   

    public LeadController(LeadRepository leadRepository, ApplicationEventPublisher eventPublisher, TurnstileService turnstileService) {
        this.leadRepository = leadRepository;
        this.eventPublisher = eventPublisher; // Initialize the event publisher
        this.turnstileService = turnstileService;
    }
    // LeadRequest is now a separate DTO class in the same package
    /**
     * Create a lead from the supplied request.
     *
     * @param req the validated lead request DTO (bean validation runs before this method)
     * @return 201 with the created lead id and a success message
     *
     * Notes:
     * - Sanitization/normalization (HTML stripping, trimming, phone normalization) is performed
     *   here before saving. Because validation runs before this method, tests must provide
     *   inputs that already satisfy validation rules (for example a well-formed email).
     * - Publishes {@link LeadCreatedEvent} after successful persistence so downstream listeners
     *   can handle email sending / external sync.
     */
    @org.springframework.transaction.annotation.Transactional
    @PostMapping
    public ResponseEntity<Map<String, Object>> createLead(@Valid @RequestBody LeadRequest req, HttpServletRequest request) {
        if (req.getCompany() != null && !req.getCompany().isBlank()) {
            logger.warn("Honeypot triggered on /api/leads");
            Map<String, Object> body = new HashMap<>();
            body.put("message", "ok");
            return ResponseEntity.ok(body);
        }

        logger.info("🔐 Starting Turnstile verification for lead request");
        TurnstileService.VerificationResult verification = turnstileService.verify(
            req.getTurnstileToken(),
            extractClientIp(request)
        );
        if (!verification.isSuccess()) {
            logger.warn("❌ Turnstile verification failed: {}", verification.getMessage());
            Map<String, Object> body = new HashMap<>();
            body.put("error", "Turnstile failed");
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
        }
        if (verification.isSkipped()) {
            logger.warn("⚠️ Turnstile verification skipped: {}", verification.getMessage());
        } else {
            logger.info("✅ Turnstile verification passed - proceeding with lead creation");
        }

        Lead lead = new Lead();
        logger.info("Received lead request: name={}, email={}, phone={}, subject={}, source={}",
            req.getName(), req.getEmail(), req.getPhone(), req.getSubject(), req.getSource());
        lead.setName(SanitizationUtils.sanitizeText(req.getName()));
        lead.setEmail(req.getEmail().trim());
        lead.setPhone(SanitizationUtils.sanitizePhone(req.getPhone()));
        if (req.getSubject() != null && !req.getSubject().isBlank()) {
            lead.setSubject(SanitizationUtils.sanitizeText(req.getSubject()));
        }
        if (req.getMessage() != null && !req.getMessage().isBlank()) {
            lead.setMessage(SanitizationUtils.sanitizeText(req.getMessage()));
        }
        if (req.getSource() != null && !req.getSource().isBlank()) {
            lead.setSource(req.getSource().trim());
        }
        leadRepository.save(lead);
        
        // Publish event after saving lead. From it goes to LeadEmailListener where the email is sent
        logger.info("Lead saved to database: id={}, name={}, email={}, phone={}", lead.getId(), lead.getName(), lead.getEmail(), lead.getPhone());
        eventPublisher.publishEvent(new LeadCreatedEvent(lead));
        logger.info("Published LeadCreatedEvent for lead id={}", lead.getId());

        // Return the created lead's ID in the response
        Map<String, Object> body = new HashMap<>();
        body.put("message", "Lead created successfully");
        body.put("id", lead.getId());
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    private String extractClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    // --- Admin endpoints (dashboard "Leads" tab) ------------------------------

    /**
     * Paged + searchable list. Match by name / email / phone / subject.
     * Sort defaults to newest first.
     */
    @GetMapping
    public ResponseEntity<?> listLeads(
            org.springframework.security.core.Authentication authentication,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        if (!isAdmin(authentication)) {
            return ResponseEntity.status(org.springframework.http.HttpStatus.FORBIDDEN).build();
        }
        var pageable = org.springframework.data.domain.PageRequest.of(
            Math.max(page, 0),
            Math.min(Math.max(size, 1), 200),
            org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.DESC, "createdAt"));
        org.springframework.data.domain.Page<Lead> pageResult;
        if (q == null || q.isBlank()) {
            pageResult = leadRepository.findAll(pageable);
        } else {
            String pattern = "%" + q.trim().toLowerCase() + "%";
            pageResult = leadRepository.searchByPattern(pattern, pageable);
        }
        Map<String, Object> response = new HashMap<>();
        response.put("content", pageResult.getContent().stream().map(this::toResponseMap).toList());
        response.put("page", pageResult.getNumber());
        response.put("size", pageResult.getSize());
        response.put("totalElements", pageResult.getTotalElements());
        response.put("totalPages", pageResult.getTotalPages());
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> getLead(
            org.springframework.security.core.Authentication authentication,
            @PathVariable java.util.UUID id) {
        if (!isAdmin(authentication)) {
            return ResponseEntity.status(org.springframework.http.HttpStatus.FORBIDDEN).build();
        }
        return leadRepository.findById(id)
            .<ResponseEntity<?>>map(lead -> ResponseEntity.ok(toResponseMap(lead)))
            .orElseGet(() -> ResponseEntity.status(org.springframework.http.HttpStatus.NOT_FOUND).build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteLead(
            org.springframework.security.core.Authentication authentication,
            @PathVariable java.util.UUID id) {
        if (!isAdmin(authentication)) {
            return ResponseEntity.status(org.springframework.http.HttpStatus.FORBIDDEN).build();
        }
        if (!leadRepository.existsById(id)) {
            return ResponseEntity.status(org.springframework.http.HttpStatus.NOT_FOUND).build();
        }
        leadRepository.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    private Map<String, Object> toResponseMap(Lead lead) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", lead.getId());
        map.put("name", lead.getName());
        map.put("email", lead.getEmail());
        map.put("phone", lead.getPhone());
        map.put("subject", lead.getSubject());
        map.put("message", lead.getMessage());
        map.put("source", lead.getSource());
        map.put("createdAt", lead.getCreatedAt());
        map.put("hubspotSyncStatus", lead.getHubspotSyncStatus());
        map.put("hubspotId", lead.getHubspotId());
        map.put("hubspotSyncedAt", lead.getHubspotSyncedAt());
        return map;
    }

    private boolean isAdmin(org.springframework.security.core.Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) return false;
        return authentication.getAuthorities().stream()
            .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
    }
}
