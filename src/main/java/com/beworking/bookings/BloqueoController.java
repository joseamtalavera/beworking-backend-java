package com.beworking.bookings;

import com.beworking.auth.EmailService;
import com.beworking.auth.User;
import com.beworking.auth.UserRepository;
import jakarta.persistence.EntityNotFoundException;
import jakarta.validation.Valid;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/bloqueos")
public class BloqueoController {

    private static final Logger logger = LoggerFactory.getLogger(BloqueoController.class);

    private final BloqueoService bloqueoService;
    private final UserRepository userRepository;
    private final EmailService emailService;
    private final JdbcTemplate jdbcTemplate;

    public BloqueoController(BloqueoService bloqueoService,
                             UserRepository userRepository,
                             EmailService emailService,
                             JdbcTemplate jdbcTemplate) {
        this.bloqueoService = bloqueoService;
        this.userRepository = userRepository;
        this.emailService = emailService;
        this.jdbcTemplate = jdbcTemplate;
    }

    @GetMapping
    public ResponseEntity<List<BloqueoResponse>> listBloqueos(
        Authentication authentication,
        @RequestParam(value = "from", required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
        @RequestParam(value = "to", required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
        @RequestParam(value = "centerId", required = false) Long centerId,
        @RequestParam(value = "contactId", required = false) Long contactId,
        @RequestParam(value = "productId", required = false) Long productId,
        @RequestParam(value = "tenantId", required = false) Long tenantIdParam
    ) {
        if (from == null && to == null) {
            LocalDate today = LocalDate.now();
            from = today.minusMonths(1);
            to = today.plusMonths(3);
        } else if (from != null && to == null) {
            to = from.plusMonths(1);
        } else if (to != null && from == null) {
            from = to.minusMonths(1);
        }

        if (authentication == null || !authentication.isAuthenticated()) {
            return ResponseEntity.status(401).build();
        }

        Optional<User> userOpt = userRepository.findByEmail(authentication.getName());
        if (userOpt.isEmpty()) {
            return ResponseEntity.status(401).build();
        }

        User user = userOpt.get();
        Long effectiveTenantId;
        Long effectiveContactId = contactId;

        if (user.getRole() == User.Role.ADMIN) {
            effectiveTenantId = tenantIdParam;
        } else {
            effectiveTenantId = user.getTenantId();
            if (effectiveTenantId == null) {
                return ResponseEntity.status(403).build();
            }
            effectiveContactId = effectiveTenantId;
        }

        List<BloqueoResponse> bloqueos = bloqueoService.getBloqueos(
            from,
            to,
            centerId,
            effectiveContactId,
            productId,
            effectiveTenantId
        );

        return ResponseEntity.ok(bloqueos);
    }

    @GetMapping("/uninvoiced")
    public ResponseEntity<List<BloqueoResponse>> getUninvoicedBloqueos(
        Authentication authentication,
        @RequestParam("contactId") Long contactId
    ) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return ResponseEntity.status(401).build();
        }
        Optional<User> userOpt = userRepository.findByEmail(authentication.getName());
        if (userOpt.isEmpty()) {
            return ResponseEntity.status(401).build();
        }
        if (userOpt.get().getRole() != User.Role.ADMIN) {
            return ResponseEntity.status(403).build();
        }
        List<BloqueoResponse> bloqueos = bloqueoService.getUninvoicedBloqueos(contactId);
        return ResponseEntity.ok(bloqueos);
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> updateBloqueo(Authentication authentication,
                                           @PathVariable Long id,
                                           @Valid @RequestBody UpdateBloqueoRequest request) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return ResponseEntity.status(401).build();
        }

        Optional<User> userOpt = userRepository.findByEmail(authentication.getName());
        if (userOpt.isEmpty()) {
            return ResponseEntity.status(401).build();
        }

        User user = userOpt.get();
        if (user.getRole() != User.Role.ADMIN) {
            return ResponseEntity.status(403).build();
        }

        try {
            BloqueoResponse response = bloqueoService.updateBloqueo(id, request);
            return ResponseEntity.ok(response);
        } catch (BookingConflictException conflictException) {
            Map<String, Object> body = new HashMap<>();
            body.put("message", conflictException.getMessage());
            body.put("conflicts", conflictException.getConflicts());
            return ResponseEntity.status(409).body(body);
        } catch (EntityNotFoundException ex) {
            return ResponseEntity.status(404).build();
        } catch (IllegalArgumentException ex) {
            Map<String, Object> body = new HashMap<>();
            body.put("message", ex.getMessage());
            return ResponseEntity.badRequest().body(body);
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteBloqueo(Authentication authentication, @PathVariable Long id) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return ResponseEntity.status(401).build();
        }

        Optional<User> userOpt = userRepository.findByEmail(authentication.getName());
        if (userOpt.isEmpty()) {
            return ResponseEntity.status(401).build();
        }

        User user = userOpt.get();
        if (user.getRole() != User.Role.ADMIN) {
            return ResponseEntity.status(403).build();
        }

        // Fetch booking details before deletion to send cancellation email
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT b.id, b.fecha_ini, b.fecha_fin, b.asistentes, b.tarifa,"
                    + " p.nombre AS producto_nombre,"
                    + " ce.nombre AS centro_nombre,"
                    + " c.name AS contact_name,"
                    + " COALESCE(c.email_primary, c.email_secondary, c.email_tertiary, c.representative_email) AS email"
                    + " FROM beworking.bloqueos b"
                    + " LEFT JOIN beworking.contact_profiles c ON c.id = b.id_cliente"
                    + " LEFT JOIN beworking.productos p ON p.id = b.id_producto"
                    + " LEFT JOIN beworking.centros ce ON ce.id = b.id_centro"
                    + " WHERE b.id = ?",
                id
            );

            if (!rows.isEmpty()) {
                Map<String, Object> row = rows.get(0);
                String email = (String) row.get("email");

                if (email != null && !email.isBlank()) {
                    String contactName = (String) row.get("contact_name");
                    String productoNombre = (String) row.get("producto_nombre");
                    String centroNombre = (String) row.get("centro_nombre");
                    Integer attendees = row.get("asistentes") != null ? ((Number) row.get("asistentes")).intValue() : null;

                    LocalDateTime fechaIni = null;
                    LocalDateTime fechaFin = null;
                    if (row.get("fecha_ini") instanceof java.sql.Timestamp ts) {
                        fechaIni = ts.toLocalDateTime();
                    }
                    if (row.get("fecha_fin") instanceof java.sql.Timestamp ts) {
                        fechaFin = ts.toLocalDateTime();
                    }

                    DateTimeFormatter dateFmt = DateTimeFormatter.ofPattern("d MMM yyyy", new Locale("es", "ES"));
                    DateTimeFormatter timeFmt = DateTimeFormatter.ofPattern("HH:mm");

                    String dateStr = fechaIni != null ? fechaIni.format(dateFmt) : "\u2014";
                    String timeFromStr = fechaIni != null ? fechaIni.format(timeFmt) : "\u2014";
                    String timeToStr = fechaFin != null ? fechaFin.format(timeFmt) : "\u2014";

                    Double tarifa = row.get("tarifa") != null ? ((Number) row.get("tarifa")).doubleValue() : null;
                    NumberFormat currencyFormat = NumberFormat.getCurrencyInstance(new Locale("es", "ES"));
                    currencyFormat.setMinimumFractionDigits(2);

                    String safeName = contactName != null ? contactName : "Cliente";
                    String safeProduct = productoNombre != null ? productoNombre : "\u2014";
                    String safeCentro = centroNombre != null ? centroNombre : "\u2014";
                    String attendeesStr = attendees != null ? String.valueOf(attendees) : null;
                    String tarifaStr = tarifa != null ? currencyFormat.format(tarifa) : null;

                    String html = "<div style='font-family:Arial,Helvetica,sans-serif;max-width:600px;margin:0 auto;background:#ffffff;border-radius:12px;overflow:hidden;box-shadow:0 2px 12px rgba(0,0,0,0.08)'>"
                        + "<div style='background:linear-gradient(135deg,#c62828 0%,#d32f2f 100%);padding:40px 32px 32px;color:#ffffff'>"
                        + "<p style='margin:0 0 4px;font-size:13px;letter-spacing:2px;text-transform:uppercase;opacity:0.85'>BEWORKING</p>"
                        + "<h1 style='margin:0 0 8px;font-size:26px;font-weight:700;line-height:1.2'>Reserva Cancelada</h1>"
                        + "</div>"
                        + "<div style='padding:32px'>"
                        + "<p style='margin:0 0 8px;font-size:16px;color:#333'>Hola <strong>" + safeName + "</strong>, tu reserva ha sido cancelada.</p>"
                        + "<p style='margin:0 0 24px;font-size:14px;color:#666'>A continuaci\u00f3n los detalles de la reserva cancelada:</p>"
                        + "<div style='background:#fef5f5;border-radius:10px;padding:20px 24px;border-left:4px solid #d32f2f'>"
                        + "<table style='border-collapse:collapse;width:100%'>"
                        + "<tr><td style='padding:8px 12px 8px 0;color:#888;font-size:13px;white-space:nowrap'>Sala</td>"
                        + "<td style='padding:8px 0;font-size:15px;font-weight:700;color:#222'>" + safeProduct + "</td></tr>"
                        + "<tr><td style='padding:8px 12px 8px 0;color:#888;font-size:13px;white-space:nowrap'>Centro</td>"
                        + "<td style='padding:8px 0;font-size:15px;color:#333'>" + safeCentro + "</td></tr>"
                        + "<tr><td style='padding:8px 12px 8px 0;color:#888;font-size:13px;white-space:nowrap'>Fecha</td>"
                        + "<td style='padding:8px 0;font-size:15px;color:#333'>" + dateStr + "</td></tr>"
                        + "<tr><td style='padding:8px 12px 8px 0;color:#888;font-size:13px;white-space:nowrap'>Horario</td>"
                        + "<td style='padding:8px 0;font-size:15px;color:#333'>" + timeFromStr + " - " + timeToStr + "</td></tr>"
                        + (attendeesStr != null ? "<tr><td style='padding:8px 12px 8px 0;color:#888;font-size:13px;white-space:nowrap'>Asistentes</td><td style='padding:8px 0;font-size:15px;color:#333'>" + attendeesStr + "</td></tr>" : "")
                        + (tarifaStr != null ? "<tr><td style='padding:8px 12px 8px 0;color:#888;font-size:13px;white-space:nowrap'>Importe</td><td style='padding:8px 0;font-size:15px;font-weight:700;color:#d32f2f'>" + tarifaStr + "</td></tr>" : "")
                        + "</table>"
                        + "</div>"
                        + "<div style='background:#fef5f5;border-radius:8px;padding:16px;margin-top:28px'>"
                        + "<p style='margin:0 0 4px;font-size:14px;font-weight:700;color:#333'>Nueva reserva</p>"
                        + "<p style='margin:0;font-size:12px;color:#888'>Si deseas realizar una nueva reserva, cont\u00e1ctanos y te ayudaremos encantados.</p>"
                        + "</div>"
                        + "<p style='margin:28px 0 0;font-size:13px;color:#888;text-align:center'>"
                        + "\u00bfNecesitas ayuda? Responde a este correo o escr\u00edbenos por WhatsApp: "
                        + "<a href='https://wa.me/34640369759' style='color:#d32f2f;text-decoration:none;font-weight:600'>+34 640 369 759</a></p>"
                        + "</div>"
                        + "<div style='background:#f9f9f9;padding:16px 32px;text-align:center;border-top:1px solid #eee'>"
                        + "<p style='margin:0;font-size:12px;color:#aaa'>\u00a9 BeWorking \u00b7 M\u00e1laga</p>"
                        + "</div>"
                        + "</div>";

                    emailService.sendHtml(email, "Tu reserva ha sido cancelada \u2014 BeWorking", html);
                    logger.info("Cancellation email sent to {} for bloqueo {}", email, id);
                }
            }
        } catch (Exception e) {
            logger.error("Failed to send cancellation email for bloqueo {}: {}", id, e.getMessage(), e);
        }

        bloqueoService.deleteBloqueo(id);
        return ResponseEntity.noContent().build();
    }
}
