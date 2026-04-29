package com.beworking.reports;

import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/reports")
public class ReportsController {

    private final InvoiceAuditService auditService;

    public ReportsController(InvoiceAuditService auditService) {
        this.auditService = auditService;
    }

    @GetMapping("/invoice-audit")
    public ResponseEntity<?> invoiceAudit(
            Authentication authentication,
            @RequestParam("month") String month,
            @RequestParam(value = "cuentas", defaultValue = "PT,OF") String cuentas) {

        if (authentication == null || !authentication.isAuthenticated()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        boolean isAdmin = authentication.getAuthorities().stream()
            .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
        if (!isAdmin) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        YearMonth ym;
        try {
            ym = YearMonth.parse(month);
        } catch (DateTimeParseException e) {
            Map<String, Object> err = new HashMap<>();
            err.put("error", "month must be YYYY-MM");
            return ResponseEntity.badRequest().body(err);
        }

        List<String> cuentaList = Arrays.stream(cuentas.split(","))
            .map(String::trim)
            .filter(s -> !s.isBlank())
            .map(String::toUpperCase)
            .toList();
        if (cuentaList.isEmpty()) {
            Map<String, Object> err = new HashMap<>();
            err.put("error", "at least one cuenta required");
            return ResponseEntity.badRequest().body(err);
        }

        return ResponseEntity.ok(auditService.buildReport(ym, cuentaList));
    }
}
