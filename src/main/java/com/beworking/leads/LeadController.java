
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

        TurnstileService.VerificationResult verification = turnstileService.verify(
            req.getTurnstileToken(),
            extractClientIp(request)
        );
        if (!verification.isSuccess()) {
            logger.warn("Turnstile verification failed: {}", verification.getMessage());
            Map<String, Object> body = new HashMap<>();
            body.put("error", "Turnstile failed");
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
        }
        if (verification.isSkipped()) {
            logger.warn("Turnstile verification skipped: {}", verification.getMessage());
        }

        Lead lead = new Lead();
        logger.info("Received lead request: name={}, email={}, phone={}", req.getName(), req.getEmail(), req.getPhone());
    lead.setName(SanitizationUtils.sanitizeText(req.getName()));
    lead.setEmail(req.getEmail().trim());
    lead.setPhone(SanitizationUtils.sanitizePhone(req.getPhone()));
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
    
    
}
