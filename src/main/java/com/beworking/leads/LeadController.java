
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
    private final com.beworking.contacts.ContactProfileRepository contactProfileRepository;
    private final com.beworking.auth.RegisterService registerService;
    private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(LeadController.class);


    public LeadController(LeadRepository leadRepository, ApplicationEventPublisher eventPublisher,
                          TurnstileService turnstileService,
                          com.beworking.contacts.ContactProfileRepository contactProfileRepository,
                          com.beworking.auth.RegisterService registerService) {
        this.leadRepository = leadRepository;
        this.eventPublisher = eventPublisher; // Initialize the event publisher
        this.turnstileService = turnstileService;
        this.contactProfileRepository = contactProfileRepository;
        this.registerService = registerService;
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

    /**
     * Patch the lead's pipeline status and/or sales-team notes.
     * Body accepts either or both of {status, notes}; missing keys preserve
     * the existing value. Stamps status_changed_at when status actually changes.
     */
    @PatchMapping("/{id}")
    public ResponseEntity<?> updateLead(
            org.springframework.security.core.Authentication authentication,
            @PathVariable java.util.UUID id,
            @RequestBody Map<String, Object> body) {
        if (!isAdmin(authentication)) {
            return ResponseEntity.status(org.springframework.http.HttpStatus.FORBIDDEN).build();
        }
        return leadRepository.findById(id).<ResponseEntity<?>>map(lead -> {
            if (body.containsKey("status")) {
                String newStatus = body.get("status") == null ? null : body.get("status").toString();
                if (newStatus != null && !newStatus.isBlank() && !newStatus.equals(lead.getStatus())) {
                    lead.setStatus(newStatus);
                    lead.setStatusChangedAt(java.time.Instant.now());
                }
            }
            if (body.containsKey("notes")) {
                Object n = body.get("notes");
                lead.setNotes(n == null ? null : n.toString());
            }
            leadRepository.save(lead);
            return ResponseEntity.ok(toResponseMap(lead));
        }).orElseGet(() -> ResponseEntity.status(org.springframework.http.HttpStatus.NOT_FOUND).build());
    }

    /**
     * Convert a lead into a Potencial contact_profile and stamp the lead as
     * 'Convertido'. Idempotent — if the email already matches an existing
     * contact, returns that contact's id and stamps the lead anyway.
     * The lead row is kept for audit; admins can delete manually if desired.
     */
    @PostMapping("/{id}/convert")
    @org.springframework.transaction.annotation.Transactional
    public ResponseEntity<?> convertLeadToContact(
            org.springframework.security.core.Authentication authentication,
            @PathVariable java.util.UUID id) {
        if (!isAdmin(authentication)) {
            return ResponseEntity.status(org.springframework.http.HttpStatus.FORBIDDEN).build();
        }
        Lead lead = leadRepository.findById(id).orElse(null);
        if (lead == null) {
            return ResponseEntity.status(org.springframework.http.HttpStatus.NOT_FOUND).build();
        }

        String emailLower = lead.getEmail() == null ? null : lead.getEmail().trim().toLowerCase();
        com.beworking.contacts.ContactProfile cp = null;
        if (emailLower != null) {
            cp = contactProfileRepository
                .findFirstByEmailPrimaryIgnoreCaseOrEmailSecondaryIgnoreCaseOrEmailTertiaryIgnoreCaseOrRepresentativeEmailIgnoreCase(
                    emailLower, emailLower, emailLower, emailLower)
                .orElse(null);
        }

        boolean created = false;
        if (cp == null) {
            cp = new com.beworking.contacts.ContactProfile();
            cp.setName(lead.getName());
            cp.setEmailPrimary(emailLower);
            cp.setPhonePrimary(lead.getPhone());
            cp.setStatus("Potencial");
            cp.setStatusChangedAt(java.time.LocalDateTime.now());
            cp.setChannel(lead.getSource() != null ? lead.getSource() : "lead");
            cp.setCreatedAt(java.time.LocalDateTime.now());
            // Carry the lead's free-text notes onto the contact, prefixed with subject for context.
            String composed = (lead.getSubject() != null ? "Asunto: " + lead.getSubject() + "\n" : "")
                + (lead.getMessage() != null ? "Mensaje original:\n" + lead.getMessage() : "")
                + (lead.getNotes() != null ? "\n\nNotas:\n" + lead.getNotes() : "");
            // ContactProfile doesn't have a `notes` column today; store on representative_notes if present,
            // otherwise drop. For now: skip — composed text could go into a future notes column.
            cp = contactProfileRepository.save(cp);
            created = true;
        }

        // Mirror admin "Nuevo Contacto" path: ensure a users row exists for the
        // contact and send a welcome email with a password-set link (24h). Without
        // this, a converted lead has no auth account — login + password-reset both
        // silently fail because findByEmail returns empty.
        boolean userCreated = false;
        if (emailLower != null && !emailLower.isBlank()) {
            try {
                com.beworking.auth.User u = registerService.createUserForContact(
                    emailLower, lead.getName(), cp.getId());
                userCreated = u != null;
            } catch (Exception e) {
                logger.warn("Lead convert: failed to create user for contact {} ({}): {}",
                    cp.getId(), emailLower, e.getMessage());
            }
        }

        lead.setStatus("Convertido");
        lead.setStatusChangedAt(java.time.Instant.now());
        leadRepository.save(lead);

        Map<String, Object> result = new HashMap<>();
        result.put("contactId", cp.getId());
        result.put("created", created);
        result.put("userCreated", userCreated);
        result.put("leadStatus", lead.getStatus());
        return ResponseEntity.ok(result);
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
        map.put("status", lead.getStatus());
        map.put("notes", lead.getNotes());
        map.put("statusChangedAt", lead.getStatusChangedAt());
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
