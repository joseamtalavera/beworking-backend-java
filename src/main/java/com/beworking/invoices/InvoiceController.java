package com.beworking.invoices;

import com.beworking.auth.EmailService;
import com.beworking.auth.User;
import com.beworking.auth.UserRepository;
import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.util.List;
import java.text.NumberFormat;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/invoices")
public class InvoiceController {

    private static final Logger logger = LoggerFactory.getLogger(InvoiceController.class);

    private final InvoiceService invoiceService;
    private final InvoicePdfService pdfService;
    private final EmailService emailService;
    private final UserRepository userRepository;

    public InvoiceController(InvoiceService invoiceService, InvoicePdfService pdfService,
                             EmailService emailService, UserRepository userRepository) {
        this.invoiceService = invoiceService;
        this.pdfService = pdfService;
        this.emailService = emailService;
        this.userRepository = userRepository;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> list(
        Authentication authentication,
        @RequestParam(value = "page", defaultValue = "0") int page,
        @RequestParam(value = "size", defaultValue = "25") int size,
        @RequestParam(value = "name", required = false) String name,
        @RequestParam(value = "email", required = false) String email,
        @RequestParam(value = "idFactura", required = false) String idFactura,
        @RequestParam(value = "status", required = false) String status,
        @RequestParam(value = "tenantType", required = false) String tenantType,
        @RequestParam(value = "product", required = false) String product,
        @RequestParam(value = "startDate", required = false) String startDate,
        @RequestParam(value = "endDate", required = false) String endDate,
        @RequestParam(value = "from", required = false) String from,
        @RequestParam(value = "to", required = false) String to
    ) {
        Optional<User> userOpt = resolveUser(authentication);
        if (userOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        User user = userOpt.get();

        // Support both startDate/endDate and from/to parameter names
        String actualStartDate = startDate != null ? startDate : from;
        String actualEndDate = endDate != null ? endDate : to;

        Long restrictedContactId = null;
        String effectiveEmail = email;
        if (user.getRole() == User.Role.USER) {
            if (user.getTenantId() != null) {
                restrictedContactId = user.getTenantId();
            } else {
                restrictedContactId = invoiceService.findContactIdByEmail(user.getEmail()).orElse(null);
            }
            if (effectiveEmail == null || effectiveEmail.trim().isEmpty()) {
                effectiveEmail = user.getEmail();
            }
        }

        InvoiceService.InvoiceFilters filters = new InvoiceService.InvoiceFilters(
            name,
            effectiveEmail,
            idFactura,
            status,
            tenantType,
            product,
            actualStartDate,
            actualEndDate,
            restrictedContactId
        );
        Page<InvoiceListItem> invoices = invoiceService.findInvoices(page, size, filters);
        BigDecimal totalRevenue = invoiceService.calculateTotalRevenue(filters);
        
        // Create a custom response that includes both invoices and total revenue
        Map<String, Object> response = new HashMap<>();
        response.put("content", invoices.getContent());
        response.put("totalElements", invoices.getTotalElements());
        response.put("totalPages", invoices.getTotalPages());
        response.put("size", invoices.getSize());
        response.put("number", invoices.getNumber());
        response.put("first", invoices.isFirst());
        response.put("last", invoices.isLast());
        response.put("totalRevenue", totalRevenue);
        
        return ResponseEntity.ok(response);
    }

    @PostMapping
    public ResponseEntity<CreateInvoiceResponse> createInvoice(
        Authentication authentication,
        @Valid @RequestBody CreateInvoiceRequest request
    ) {
        Optional<User> userOpt = resolveUser(authentication);
        if (userOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        User user = userOpt.get();
        if (user.getRole() != User.Role.ADMIN) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        CreateInvoiceResponse response = invoiceService.createInvoice(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/manual")
    public ResponseEntity<Map<String, Object>> createManualInvoice(
        Authentication authentication,
        @Valid @RequestBody CreateManualInvoiceRequest request
    ) {
        Optional<User> userOpt = resolveUser(authentication);
        if (userOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        User user = userOpt.get();
        if (user.getRole() != User.Role.ADMIN) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        try {
            Map<String, Object> response = invoiceService.createManualInvoice(request);
            return ResponseEntity.status(HttpStatus.CREATED).body(response);
        } catch (Exception e) {
            Map<String, Object> error = new HashMap<>();
            error.put("error", "Failed to create manual invoice: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
        }
    }

    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> getInvoice(
        Authentication authentication,
        @PathVariable Long id
    ) {
        Optional<User> userOpt = resolveUser(authentication);
        if (userOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (userOpt.get().getRole() != User.Role.ADMIN) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        try {
            Map<String, Object> result = invoiceService.getInvoiceDetail(id);
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            Map<String, Object> error = new HashMap<>();
            error.put("error", e.getMessage());
            return ResponseEntity.badRequest().body(error);
        }
    }

    @PutMapping("/{id}")
    public ResponseEntity<Map<String, Object>> updateInvoice(
        Authentication authentication,
        @PathVariable Long id,
        @Valid @RequestBody CreateManualInvoiceRequest request
    ) {
        Optional<User> userOpt = resolveUser(authentication);
        if (userOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (userOpt.get().getRole() != User.Role.ADMIN) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        try {
            Map<String, Object> result = invoiceService.updateInvoice(id, request);
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            Map<String, Object> error = new HashMap<>();
            error.put("error", e.getMessage());
            return ResponseEntity.badRequest().body(error);
        } catch (Exception e) {
            logger.error("Failed to update invoice {}: {}", id, e.getMessage(), e);
            Map<String, Object> error = new HashMap<>();
            error.put("error", "Failed to update invoice: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
        }
    }

    @PatchMapping("/{id}/status")
    public ResponseEntity<Map<String, Object>> updateStatus(
        Authentication authentication,
        @PathVariable Long id,
        @RequestBody Map<String, String> body
    ) {
        Optional<User> userOpt = resolveUser(authentication);
        if (userOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (userOpt.get().getRole() != User.Role.ADMIN) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        String status = body.get("status");
        if (status == null || status.isBlank()) {
            Map<String, Object> error = new HashMap<>();
            error.put("error", "status is required");
            return ResponseEntity.badRequest().body(error);
        }

        try {
            Map<String, Object> result = invoiceService.updateInvoiceStatus(id, status);
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            Map<String, Object> error = new HashMap<>();
            error.put("error", e.getMessage());
            return ResponseEntity.badRequest().body(error);
        }
    }

    @GetMapping("/pdf-url")
    public ResponseEntity<String> pdfUrl(@RequestParam("id") Long id) {
        return invoiceService.resolvePdfUrl(id)
            .map(uri -> ResponseEntity.ok(uri.toString()))
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/next-number")
    public ResponseEntity<Map<String, Object>> getNextInvoiceNumber(
        @RequestParam(value = "cuentaId", required = false) Integer cuentaId,
        @RequestParam(value = "cuentaCodigo", required = false) String cuentaCodigo
    ) {
        try {
            String nextNumber;
            if (cuentaId != null) {
                nextNumber = invoiceService.getNextInvoiceNumber(cuentaId);
            } else if (cuentaCodigo != null) {
                nextNumber = invoiceService.getNextInvoiceNumber(cuentaCodigo);
            } else {
                // Default to Partners cuenta for backward compatibility
                nextNumber = invoiceService.getNextInvoiceNumber();
            }
            
            Map<String, Object> response = new HashMap<>();
            response.put("nextNumber", nextNumber);
            if (cuentaId != null) response.put("cuentaId", cuentaId);
            if (cuentaCodigo != null) response.put("cuentaCodigo", cuentaCodigo);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            Map<String, Object> error = new HashMap<>();
            error.put("error", "Failed to get next invoice number: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
        }
    }

    @PostMapping("/{id}/credit")
    public ResponseEntity<Map<String, Object>> creditInvoice(
        Authentication authentication,
        @PathVariable Long id
    ) {
        Optional<User> userOpt = resolveUser(authentication);
        if (userOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (userOpt.get().getRole() != User.Role.ADMIN) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        try {
            Map<String, Object> result = invoiceService.creditInvoice(id);
            return ResponseEntity.status(HttpStatus.CREATED).body(result);
        } catch (IllegalArgumentException e) {
            Map<String, Object> error = new HashMap<>();
            error.put("error", e.getMessage());
            return ResponseEntity.badRequest().body(error);
        } catch (Exception e) {
            logger.error("Failed to credit invoice {}: {}", id, e.getMessage(), e);
            Map<String, Object> error = new HashMap<>();
            error.put("error", "Failed to create credit note: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
        }
    }

    @PostMapping("/{id}/send-email")
    public ResponseEntity<Map<String, Object>> sendInvoiceEmail(
        Authentication authentication,
        @PathVariable Long id
    ) {
        Optional<User> userOpt = resolveUser(authentication);
        if (userOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (userOpt.get().getRole() != User.Role.ADMIN) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        try {
            String clientEmail = pdfService.getClientEmail(id);
            String clientName = pdfService.getClientName(id);
            String displayNumber = pdfService.getDisplayNumber(id);
            BigDecimal total = pdfService.getInvoiceTotal(id);

            if (clientEmail == null || clientEmail.isBlank()) {
                Map<String, Object> error = new HashMap<>();
                error.put("error", "No email found for this invoice's contact");
                return ResponseEntity.badRequest().body(error);
            }

            byte[] pdfBytes = pdfService.generatePdf(id);
            if (pdfBytes == null) {
                return ResponseEntity.notFound().build();
            }

            NumberFormat currencyFormat = NumberFormat.getCurrencyInstance(new Locale("es", "ES"));
            currencyFormat.setMinimumFractionDigits(2);
            String totalStr = total != null ? currencyFormat.format(total) : "—";
            String safeName = clientName != null ? clientName : "Cliente";

            String html = "<div style='font-family:Arial,sans-serif;max-width:600px;margin:0 auto'>"
                + "<div style='background:#009624;padding:16px;text-align:center'>"
                + "<img src='https://app.be-working.com/beworking_logo.png' alt='BeWorking' style='height:40px' />"
                + "</div>"
                + "<div style='padding:24px'>"
                + "<p>Estimado/a " + safeName + ",</p>"
                + "<p>Adjuntamos su factura <strong>#" + displayNumber + "</strong> por importe de <strong>" + totalStr + "</strong>.</p>"
                + "<h3 style='color:#009624;margin-top:24px'>Datos para transferencia bancaria</h3>"
                + "<table style='border-collapse:collapse'>"
                + "<tr><td style='padding:4px 12px 4px 0;color:#666'>IBAN:</td><td><strong>ES82 0182 2116 0102 0171 0670</strong></td></tr>"
                + "<tr><td style='padding:4px 12px 4px 0;color:#666'>Titular:</td><td>BeWorking Partners Offices SL</td></tr>"
                + "<tr><td style='padding:4px 12px 4px 0;color:#666'>Concepto:</td><td>Factura #" + displayNumber + "</td></tr>"
                + "</table>"
                + "<p style='margin-top:24px;color:#666;font-size:13px'>Gracias por ser parte de la comunidad BeWorking.</p>"
                + "</div></div>";

            String subject = "Factura #" + displayNumber + " - BeWorking";
            String attachmentName = "factura-" + displayNumber + ".pdf";

            emailService.sendHtmlWithAttachment(clientEmail, subject, html, pdfBytes, attachmentName);

            Map<String, Object> response = new HashMap<>();
            response.put("message", "Invoice email sent to " + clientEmail);
            response.put("invoiceNumber", displayNumber);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            logger.error("Failed to send invoice email for id {}: {}", id, e.getMessage(), e);
            Map<String, Object> error = new HashMap<>();
            error.put("error", "Failed to send invoice email: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
        }
    }

    @GetMapping("/orphaned-bloqueos")
    public ResponseEntity<Map<String, Object>> findOrphanedBloqueos(Authentication authentication) {
        Optional<User> userOpt = resolveUser(authentication);
        if (userOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (userOpt.get().getRole() != User.Role.ADMIN) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        List<Map<String, Object>> orphaned = invoiceService.findOrphanedInvoicedBloqueos();
        Map<String, Object> response = new HashMap<>();
        response.put("count", orphaned.size());
        response.put("bloqueos", orphaned);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/orphaned-bloqueos/fix")
    public ResponseEntity<Map<String, Object>> fixOrphanedBloqueos(Authentication authentication) {
        Optional<User> userOpt = resolveUser(authentication);
        if (userOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (userOpt.get().getRole() != User.Role.ADMIN) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        try {
            List<Map<String, Object>> created = invoiceService.fixOrphanedInvoicedBloqueos();
            Map<String, Object> response = new HashMap<>();
            response.put("fixed", created.size());
            response.put("invoices", created);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            logger.error("Failed to fix orphaned bloqueos: {}", e.getMessage(), e);
            Map<String, Object> error = new HashMap<>();
            error.put("error", "Failed to fix orphaned bloqueos: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
        }
    }

    private Optional<User> resolveUser(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return Optional.empty();
        }
        return userRepository.findByEmail(authentication.getName());
    }
}
