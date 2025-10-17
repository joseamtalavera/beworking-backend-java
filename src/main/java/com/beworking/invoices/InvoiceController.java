package com.beworking.invoices;

import com.beworking.auth.User;
import com.beworking.auth.UserRepository;
import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/invoices")
public class InvoiceController {

    private final InvoiceService invoiceService;
    private final UserRepository userRepository;

    public InvoiceController(InvoiceService invoiceService, UserRepository userRepository) {
        this.invoiceService = invoiceService;
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

    private Optional<User> resolveUser(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return Optional.empty();
        }
        return userRepository.findByEmail(authentication.getName());
    }
}
