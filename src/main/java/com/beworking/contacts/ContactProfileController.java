package com.beworking.contacts;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import com.beworking.auth.RegisterService;
import com.beworking.auth.User;
import com.beworking.auth.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@RestController
@RequestMapping("/api/contact-profiles")
@CrossOrigin(origins = {"http://localhost:5173", "http://localhost:5174"})
public class ContactProfileController {

    private static final Logger logger = LoggerFactory.getLogger(ContactProfileController.class);
    private final ContactProfileService contactProfileService;
    private final ContactProfileRepository contactProfileRepository;
    private final UserRepository userRepository;
    private final JdbcTemplate jdbcTemplate;
    private final ViesVatService viesVatService;
    private final RegisterService registerService;
    private final com.beworking.subscriptions.SubscriptionService subscriptionService;
    private final com.beworking.auth.EmailService emailService;
    private final org.springframework.context.ApplicationEventPublisher eventPublisher;

    public ContactProfileController(ContactProfileService contactProfileService,
                                     ContactProfileRepository contactProfileRepository,
                                     UserRepository userRepository,
                                     JdbcTemplate jdbcTemplate,
                                     ViesVatService viesVatService,
                                     RegisterService registerService,
                                     com.beworking.subscriptions.SubscriptionService subscriptionService,
                                     com.beworking.auth.EmailService emailService,
                                     org.springframework.context.ApplicationEventPublisher eventPublisher) {
        this.contactProfileService = contactProfileService;
        this.contactProfileRepository = contactProfileRepository;
        this.userRepository = userRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.viesVatService = viesVatService;
        this.registerService = registerService;
        this.subscriptionService = subscriptionService;
        this.emailService = emailService;
        this.eventPublisher = eventPublisher;
    }

    @GetMapping
    public ResponseEntity<ContactProfilesPageResponse> getAllContactProfiles(
        Authentication authentication,
        @RequestParam(value = "page", defaultValue = "0") int page,
        @RequestParam(value = "size", defaultValue = "25") int size,
        @RequestParam(value = "search", required = false) String search,
        @RequestParam(value = "status", required = false) String status,
        @RequestParam(value = "plan", required = false) String plan,
        @RequestParam(value = "tenantType", required = false) String tenantType,
        @RequestParam(value = "email", required = false) String email,
        @RequestParam(value = "startDate", required = false) String startDate,
        @RequestParam(value = "endDate", required = false) String endDate
    ) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        String userEmail = authentication.getName();
        User user = userRepository.findByEmail(userEmail).orElse(null);
        
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        // For users, filter by their tenantId to show only their own contact
        // For admins, show all contacts
        System.out.println("DEBUG: User role: " + user.getRole() + " (USER: " + User.Role.USER + ", ADMIN: " + User.Role.ADMIN + ")");
        System.out.println("DEBUG: Role comparison: " + (user.getRole() == User.Role.USER));
        if (user.getRole() == User.Role.USER) {
            // Users can only see their own contact
            if (user.getTenantId() != null) {
                // Filter to only show the user's own contact
                ContactProfilesPageResponse profiles = contactProfileService.getContactProfilesByTenantId(
                    user.getTenantId(), page, size, search, status, plan, tenantType, email, startDate, endDate
                );
                return ResponseEntity.ok(profiles);
            } else {
                // User has no tenantId, try to find their contact by email
                ContactProfilesPageResponse profiles = contactProfileService.getContactProfilesByEmail(
                    user.getEmail(), page, size, search, status, plan, tenantType, email, startDate, endDate
                );
                return ResponseEntity.ok(profiles);
            }
        } else {
            // Admins can see all contacts
            ContactProfilesPageResponse profiles = contactProfileService.getContactProfiles(page, size, search, status, plan, tenantType, email, startDate, endDate);
            return ResponseEntity.ok(profiles);
        }
    }

    @GetMapping("/{id}")
    public ResponseEntity<ContactProfile> getContactProfileById(
        @PathVariable Long id,
        Authentication authentication
    ) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        String userEmail = authentication.getName();
        User user = userRepository.findByEmail(userEmail).orElse(null);
        
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        try {
            ContactProfile profile = contactProfileService.getContactProfileById(id);
            
            // For users, only allow access to their own contact
            if (user.getRole() == User.Role.USER) {
                if (user.getTenantId() != null && !user.getTenantId().equals(profile.getId())) {
                    return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
                }
            }
            
            return ResponseEntity.ok(profile);
        } catch (ContactProfileService.ContactProfileNotFoundException e) {
            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @PostMapping
    public ResponseEntity<ContactProfile> createContactProfile(@RequestBody ContactProfileRequest request) {
        try {
            ContactProfile createdProfile = contactProfileService.createContactProfile(request);
            return ResponseEntity.status(HttpStatus.CREATED).body(createdProfile);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @PostMapping("/sync-users")
    public ResponseEntity<Map<String, Object>> syncContactUsers(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        User adminUser = userRepository.findByEmail(authentication.getName()).orElse(null);
        if (adminUser == null || adminUser.getRole() != User.Role.ADMIN) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        List<Map<String, Object>> contacts = jdbcTemplate.queryForList(
            "SELECT id, email_primary, contact_name, name FROM beworking.contact_profiles WHERE COALESCE(TRIM(email_primary), '') != '' ORDER BY id");

        int created = 0;
        int alreadyLinked = 0;
        int skipped = 0;
        java.util.ArrayList<String> errors = new java.util.ArrayList<>();

        for (Map<String, Object> cp : contacts) {
            String email = (String) cp.get("email_primary");
            if (email == null || email.isBlank()) {
                skipped++;
                continue;
            }
            try {
                var existing = userRepository.findByEmail(email.trim().toLowerCase());
                if (existing.isPresent()) {
                    User user = existing.get();
                    if (user.getTenantId() == null) {
                        user.setTenantId((Long) cp.get("id"));
                        userRepository.save(user);
                    }
                    alreadyLinked++;
                } else {
                    String name = cp.get("contact_name") != null && !((String) cp.get("contact_name")).isBlank()
                        ? (String) cp.get("contact_name") : (String) cp.get("name");
                    registerService.createUserForContactSilent(email, name, (Long) cp.get("id"));
                    created++;
                }
            } catch (Exception e) {
                errors.add(cp.get("id") + ": " + e.getMessage());
            }
        }

        Map<String, Object> result = new HashMap<>();
        result.put("created", created);
        result.put("alreadyLinked", alreadyLinked);
        result.put("skipped", skipped);
        result.put("total", contacts.size());
        if (!errors.isEmpty()) {
            result.put("errors", errors);
        }
        return ResponseEntity.ok(result);
    }

    @PutMapping("/{id}")
    public ResponseEntity<ContactProfile> updateContactProfile(
        @PathVariable Long id,
        @RequestBody ContactProfileRequest request
    ) {
        try {
            ContactProfile updatedProfile = contactProfileService.updateContactProfile(id, request);
            return ResponseEntity.ok(updatedProfile);
        } catch (ContactProfileService.ContactProfileNotFoundException e) {
            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            logger.error("Failed to update contact profile {}: {}", id, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @GetMapping("/audit")
    public ResponseEntity<Map<String, Object>> auditContacts(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        User user = userRepository.findByEmail(authentication.getName()).orElse(null);
        if (user == null || user.getRole() != User.Role.ADMIN) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        // Contacts with no name (name, contact_name, and billing_name all empty)
        List<Map<String, Object>> noName = jdbcTemplate.queryForList("""
            SELECT c.id, c.email_primary, c.status, c.tenant_type, c.created_at
            FROM beworking.contact_profiles c
            WHERE COALESCE(TRIM(c.name), '') = ''
              AND COALESCE(TRIM(c.contact_name), '') = ''
              AND COALESCE(TRIM(c.billing_name), '') = ''
            ORDER BY c.id DESC
            """);

        // Contacts with no email (all 4 email fields empty)
        List<Map<String, Object>> noEmail = jdbcTemplate.queryForList("""
            SELECT c.id, c.name, c.contact_name, c.status, c.tenant_type, c.created_at
            FROM beworking.contact_profiles c
            WHERE COALESCE(TRIM(c.email_primary), '') = ''
              AND COALESCE(TRIM(c.email_secondary), '') = ''
              AND COALESCE(TRIM(c.email_tertiary), '') = ''
              AND COALESCE(TRIM(c.representative_email), '') = ''
            ORDER BY c.id DESC
            """);

        // Contacts with no invoices
        List<Map<String, Object>> noInvoices = jdbcTemplate.queryForList("""
            SELECT c.id, COALESCE(c.name, c.contact_name, c.billing_name) AS name,
                   c.email_primary, c.status, c.tenant_type, c.created_at
            FROM beworking.contact_profiles c
            WHERE c.id NOT IN (
                SELECT DISTINCT f.idcliente FROM beworking.facturas f WHERE f.idcliente IS NOT NULL
            )
            ORDER BY c.id DESC
            """);

        Map<String, Object> response = new HashMap<>();
        response.put("noName", Map.of("count", noName.size(), "contacts", noName));
        response.put("noEmail", Map.of("count", noEmail.size(), "contacts", noEmail));
        response.put("noInvoices", Map.of("count", noInvoices.size(), "contacts", noInvoices));

        return ResponseEntity.ok(response);
    }

    @GetMapping("/vat/validate")
    public ResponseEntity<Map<String, Object>> validateVat(
            @RequestParam String vatNumber,
            @RequestParam(required = false) String countryHint,
            @RequestParam(required = false) Long contactId) {
        ViesVatService.VatValidationResult result = viesVatService.validate(vatNumber, countryHint);
        if (contactId != null) {
            try {
                contactProfileService.revalidateVat(contactId);
            } catch (Exception e) {
                logger.warn("Failed to persist VAT validation for contact {}: {}", contactId, e.getMessage());
            }
        }
        Map<String, Object> body = new HashMap<>();
        body.put("valid", result.valid());
        body.put("name", result.name());
        body.put("address", result.address());
        body.put("error", result.error());
        return ResponseEntity.ok(body);
    }

    /**
     * Admin trigger: re-validate one contact's VAT against VIES, promote their
     * tax-ID type if vat_valid flipped TRUE, and re-lock vat_percent on every
     * active subscription they have. This is the support flow for cohort B
     * customers who say "I'm actually VIES-registered, why am I being charged
     * VAT?". Synchronous (one VIES call + a few DB writes), no throttling.
     */
    @PostMapping("/{id}/revalidate-vat")
    public ResponseEntity<Map<String, Object>> revalidateContactVat(
            Authentication authentication,
            @PathVariable Long id) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        boolean isAdmin = authentication.getAuthorities().stream()
            .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
        if (!isAdmin) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        // Snapshot the before-state for the response.
        Map<String, Object> before;
        try {
            before = jdbcTemplate.queryForMap(
                "SELECT vat_valid, billing_tax_id_type FROM beworking.contact_profiles WHERE id = ?", id);
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            return ResponseEntity.notFound().build();
        }

        // 1. Force VIES check + persist new vat_valid. ContactProfileService.revalidateVat
        //    bypasses the cache and writes definitively.
        boolean nowValid = contactProfileService.revalidateVat(id);

        // 2. Promote es_cif → eu_vat if VIES just flipped to TRUE on a Spanish
        //    company. Idempotent.
        if (nowValid && "es_cif".equals(before.get("billing_tax_id_type"))) {
            jdbcTemplate.update(
                "UPDATE beworking.contact_profiles SET billing_tax_id_type = 'eu_vat' WHERE id = ? AND billing_tax_id_type = 'es_cif'",
                id);
        }

        // 3. Re-lock vat_percent on every active sub of this contact.
        java.util.List<com.beworking.subscriptions.Subscription> subs =
            subscriptionService.findByContactIdAndActiveTrue(id);
        java.util.List<Map<String, Object>> relocked = new java.util.ArrayList<>();
        for (var sub : subs) {
            var result = subscriptionService.relockVatPercent(sub);
            relocked.add(Map.of(
                "subId", result.subId(),
                "previousVatPercent", result.previousVatPercent() != null ? result.previousVatPercent() : "null",
                "newVatPercent", result.newVatPercent(),
                "changed", result.changed()
            ));
        }

        // Snapshot after-state for the response.
        Map<String, Object> after = jdbcTemplate.queryForMap(
            "SELECT vat_valid, billing_tax_id_type FROM beworking.contact_profiles WHERE id = ?", id);

        // Push the final identity (post type-flip + relock) to Stripe so the
        // customer's name / tax id / tax-exempt match our DB.
        eventPublisher.publishEvent(new ContactBillingChangedEvent(id));

        Map<String, Object> body = new HashMap<>();
        body.put("contactId", id);
        body.put("vatValidBefore", before.get("vat_valid"));
        body.put("vatValidAfter", after.get("vat_valid"));
        body.put("typeBefore", before.get("billing_tax_id_type"));
        body.put("typeAfter", after.get("billing_tax_id_type"));
        body.put("subscriptionsRelocked", relocked);
        return ResponseEntity.ok(body);
    }

    private static final java.util.concurrent.atomic.AtomicBoolean RESEED_IN_PROGRESS =
        new java.util.concurrent.atomic.AtomicBoolean(false);

    @PostMapping("/vat/revalidate-all")
    public ResponseEntity<Map<String, Object>> revalidateAllVat(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        boolean isAdmin = authentication.getAuthorities().stream()
            .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
        if (!isAdmin) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        // Fire-and-forget: ~1972 contacts × 1s VIES throttle ≈ 33 min, far over
        // the ALB's idle timeout. Run in a background thread; results land in
        // the backend log. Lock prevents two reseeds racing each other.
        if (!RESEED_IN_PROGRESS.compareAndSet(false, true)) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                "status", "rejected",
                "message", "VIES reseed already in progress"
            ));
        }

        new Thread(() -> {
            long start = System.currentTimeMillis();
            logger.info("VIES bulk reseed starting (admin={})",
                authentication.getName());
            try {
                Map<String, Integer> stats = contactProfileService.revalidateAllStaleVat();
                long elapsedMin = (System.currentTimeMillis() - start) / 60_000;
                logger.info("VIES bulk reseed finished in {} min: {}", elapsedMin, stats);
            } catch (Exception e) {
                logger.error("VIES bulk reseed failed", e);
            } finally {
                RESEED_IN_PROGRESS.set(false);
            }
        }, "vies-bulk-reseed").start();

        return ResponseEntity.accepted().body(Map.of(
            "status", "started",
            "message", "VIES revalidation running in background. Check backend logs for progress and final counts.",
            "estimatedMinutes", 33
        ));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> deleteContactProfile(@PathVariable Long id) {
        try {
            boolean deleted = contactProfileService.deleteContactProfile(id);
            if (deleted) {
                return ResponseEntity.noContent().build();
            } else {
                return ResponseEntity.notFound().build();
            }
        } catch (ContactHasInvoicesException e) {
            // Blocked: invoices are linked. Tell the client how many so the UI
            // can explain why deletion is refused.
            Map<String, Object> body = new HashMap<>();
            body.put("error", "contact_has_invoices");
            body.put("message", e.getMessage());
            body.put("invoiceCount", e.getInvoiceCount());
            return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
        } catch (Exception e) {
            logger.error("Failed to delete contact profile {}: {}", id, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * Bulk-trigger the recovery email send-now action for a list of contacts.
     * Each row is processed independently — invalid IDs / non-Potencial
     * states / exhausted sequences are reported back in {@code skipped}.
     * Used by the Contacts table bulk-action toolbar.
     */
    @PostMapping("/bulk-send-recovery")
    public ResponseEntity<Map<String, Object>> bulkSendRecovery(
        @RequestBody Map<String, Object> body,
        Authentication authentication
    ) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        boolean isAdmin = authentication.getAuthorities().stream()
            .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
        if (!isAdmin) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        @SuppressWarnings("unchecked")
        List<Object> rawIds = body.get("ids") instanceof List ? (List<Object>) body.get("ids") : List.of();
        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        int sent = 0;
        int skipped = 0;
        for (Object raw : rawIds) {
            Long id;
            try { id = Long.valueOf(raw.toString()); }
            catch (Exception e) { skipped++; continue; }

            ContactProfile cp = contactProfileRepository.findById(id).orElse(null);
            if (cp == null || !"Potencial".equals(cp.getStatus())) { skipped++; continue; }
            int already = cp.getAbandonmentEmailCount();
            if (already >= 4) { skipped++; continue; }
            String email = cp.getEmailPrimary();
            if (email == null || email.isBlank()) { skipped++; continue; }

            int templateNumber = already + 1;
            cp.setAbandonmentEmailCount(templateNumber);
            cp.setLastRecoveryEmailAt(now);
            if (templateNumber == 1) cp.setAbandonmentEmailSentAt(now);
            contactProfileRepository.save(cp);
            emailService.sendRecoveryEmail(email, cp.getName(), templateNumber, cp.getId());
            sent++;
        }
        Map<String, Object> result = new HashMap<>();
        result.put("requested", rawIds.size());
        result.put("sent", sent);
        result.put("skipped", skipped);
        return ResponseEntity.ok(result);
    }

    /**
     * Bulk-trigger the reengagement email for Inactivo contacts. Same shape
     * as bulk-send-recovery but advances reengagement_email_count instead.
     * Honours the 3-attempt cap and 6-month minimum interval.
     */
    @PostMapping("/bulk-send-reengagement")
    public ResponseEntity<Map<String, Object>> bulkSendReengagement(
        @RequestBody Map<String, Object> body,
        Authentication authentication
    ) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        boolean isAdmin = authentication.getAuthorities().stream()
            .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
        if (!isAdmin) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        @SuppressWarnings("unchecked")
        List<Object> rawIds = body.get("ids") instanceof List ? (List<Object>) body.get("ids") : List.of();
        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        java.time.Duration sixMonths = java.time.Duration.ofDays(180);
        int sent = 0;
        int skipped = 0;
        for (Object raw : rawIds) {
            Long id;
            try { id = Long.valueOf(raw.toString()); }
            catch (Exception e) { skipped++; continue; }

            ContactProfile cp = contactProfileRepository.findById(id).orElse(null);
            if (cp == null || !"Inactivo".equals(cp.getStatus())) { skipped++; continue; }
            int already = cp.getReengagementEmailCount();
            if (already >= 3) { skipped++; continue; }
            java.time.LocalDateTime last = cp.getLastReengagementEmailAt();
            if (last != null && java.time.Duration.between(last, now).compareTo(sixMonths) < 0) {
                skipped++; continue;
            }
            String email = cp.getEmailPrimary();
            if (email == null || email.isBlank()) { skipped++; continue; }

            cp.setReengagementEmailCount(already + 1);
            cp.setLastReengagementEmailAt(now);
            contactProfileRepository.save(cp);
            emailService.sendReengagementEmail(email, cp.getName(), cp.getId(), already + 1);
            sent++;
        }
        Map<String, Object> result = new HashMap<>();
        result.put("requested", rawIds.size());
        result.put("sent", sent);
        result.put("skipped", skipped);
        return ResponseEntity.ok(result);
    }

    /**
     * CSV export of contacts matching the current filters. Streams
     * {@code text/csv} to the browser. Same status/search/userType filters
     * as the list endpoint; "all" or omitted means every contact.
     */
    @GetMapping(value = "/export.csv", produces = "text/csv")
    public ResponseEntity<String> exportCsv(
        @org.springframework.web.bind.annotation.RequestParam(value = "status", required = false) String status,
        @org.springframework.web.bind.annotation.RequestParam(value = "search", required = false) String search,
        @org.springframework.web.bind.annotation.RequestParam(value = "userType", required = false) String userType,
        Authentication authentication
    ) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        boolean isAdmin = authentication.getAuthorities().stream()
            .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
        if (!isAdmin) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        StringBuilder sql = new StringBuilder("""
            SELECT id, name, email_primary, status, user_type, phone_primary,
                   created_at, status_changed_at, abandonment_email_count,
                   reengagement_email_count
              FROM beworking.contact_profiles
             WHERE 1=1
            """);
        List<Object> args = new java.util.ArrayList<>();
        if (status != null && !status.isBlank() && !"all".equalsIgnoreCase(status)) {
            sql.append(" AND status = ?");
            args.add(status);
        }
        if (userType != null && !userType.isBlank() && !"all".equalsIgnoreCase(userType)) {
            sql.append(" AND user_type = ?");
            args.add(userType);
        }
        if (search != null && !search.isBlank()) {
            sql.append(" AND (LOWER(name) LIKE ? OR LOWER(email_primary) LIKE ?)");
            String like = "%" + search.toLowerCase() + "%";
            args.add(like);
            args.add(like);
        }
        sql.append(" ORDER BY created_at DESC");

        StringBuilder csv = new StringBuilder();
        csv.append("id,name,email,status,user_type,phone,created_at,status_changed_at,recovery_emails_sent,reengagement_emails_sent\n");
        jdbcTemplate.query(sql.toString(), rs -> {
            csv.append(csvCell(rs.getObject("id")));
            csv.append(',').append(csvCell(rs.getString("name")));
            csv.append(',').append(csvCell(rs.getString("email_primary")));
            csv.append(',').append(csvCell(rs.getString("status")));
            csv.append(',').append(csvCell(rs.getString("user_type")));
            csv.append(',').append(csvCell(rs.getString("phone_primary")));
            csv.append(',').append(csvCell(rs.getObject("created_at")));
            csv.append(',').append(csvCell(rs.getObject("status_changed_at")));
            csv.append(',').append(csvCell(rs.getObject("abandonment_email_count")));
            csv.append(',').append(csvCell(rs.getObject("reengagement_email_count")));
            csv.append('\n');
        }, args.toArray());

        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        headers.add("Content-Disposition",
            "attachment; filename=contacts-" + java.time.LocalDate.now() + ".csv");
        return new ResponseEntity<>(csv.toString(), headers, HttpStatus.OK);
    }

    private static String csvCell(Object value) {
        if (value == null) return "";
        String s = value.toString();
        if (s.contains(",") || s.contains("\"") || s.contains("\n")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }

    /**
     * Funnel snapshot for the chip-row at the top of the Contacts table.
     * Returns counts for the three canonical statuses so the UI can render
     * one-click filters without re-fetching the whole list.
     */
    @GetMapping("/funnel-counts")
    public ResponseEntity<Map<String, Long>> funnelCounts(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        Map<String, Long> counts = new HashMap<>();
        for (String status : List.of("Activo", "Potencial", "Inactivo")) {
            long n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM beworking.contact_profiles WHERE status = ?",
                Long.class, status);
            counts.put(status, n);
        }
        return ResponseEntity.ok(counts);
    }

    /**
     * Manually advance one contact through the recovery sequence right now
     * (rather than waiting for the hourly cron). Sends the next template in
     * line based on abandonment_email_count, stamps the row, and returns the
     * template number that was dispatched. Useful for warm leads admin wants
     * to push without waiting up to 60 minutes.
     */
    @PostMapping("/{id}/abandonment/send-now")
    public ResponseEntity<Map<String, Object>> sendRecoveryNow(
        @PathVariable Long id,
        Authentication authentication
    ) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        boolean isAdmin = authentication.getAuthorities().stream()
            .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
        if (!isAdmin) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        ContactProfile cp = contactProfileRepository.findById(id).orElse(null);
        if (cp == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        String email = cp.getEmailPrimary();
        if (email == null || email.isBlank()) {
            Map<String, Object> err = new HashMap<>();
            err.put("error", "Contact has no primary email");
            return ResponseEntity.badRequest().body(err);
        }
        int alreadySent = cp.getAbandonmentEmailCount();
        if (alreadySent >= 4) {
            Map<String, Object> err = new HashMap<>();
            err.put("error", "Recovery sequence already exhausted (4/4 sent)");
            return ResponseEntity.status(HttpStatus.CONFLICT).body(err);
        }

        int templateNumber = alreadySent + 1;
        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        cp.setAbandonmentEmailCount(templateNumber);
        cp.setLastRecoveryEmailAt(now);
        if (templateNumber == 1) {
            cp.setAbandonmentEmailSentAt(now);
        }
        contactProfileRepository.save(cp);

        emailService.sendRecoveryEmail(email, cp.getName(), templateNumber);

        Map<String, Object> result = new HashMap<>();
        result.put("contactId", id);
        result.put("templateSent", templateNumber);
        result.put("totalSent", templateNumber);
        return ResponseEntity.ok(result);
    }

    /**
     * Manually trigger the recovery email batch for every contact at
     * status='Potencial' who hasn't yet received email #1. Does NOT cycle
     * through #2/#3/#4 — those are owned by the hourly cron. Use this for
     * one-off backfills (e.g. immediately after a status migration).
     * info@be-working.com is BCC'd so the team can pick up replies.
     */
    @PostMapping("/abandonment/send-batch")
    public ResponseEntity<Map<String, Object>> sendAbandonmentBatch(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        boolean isAdmin = authentication.getAuthorities().stream()
            .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
        if (!isAdmin) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        java.time.LocalDateTime windowStart = now.minusDays(7);
        List<ContactProfile> targets = contactProfileRepository
            .findByStatusAndCreatedAtGreaterThanEqualAndAbandonmentEmailCountLessThan(
                "Potencial", windowStart, 1);

        int sent = 0;
        int skipped = 0;
        for (ContactProfile cp : targets) {
            String email = cp.getEmailPrimary();
            if (email == null || email.isBlank()) {
                skipped++;
                continue;
            }
            cp.setAbandonmentEmailCount(1);
            cp.setLastRecoveryEmailAt(now);
            cp.setAbandonmentEmailSentAt(now);
            contactProfileRepository.save(cp);

            emailService.sendRecoveryEmail(email, cp.getName(), 1, cp.getId());
            sent++;
        }

        Map<String, Object> result = new HashMap<>();
        result.put("targeted", targets.size());
        result.put("sent", sent);
        result.put("skipped", skipped);
        result.put("status", "Potencial");
        return ResponseEntity.ok(result);
    }
}
