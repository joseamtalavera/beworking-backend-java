package com.beworking.bookings;

import com.beworking.auth.EmailService;
import com.beworking.auth.User;
import com.beworking.auth.UserRepository;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    private static final Logger logger = LoggerFactory.getLogger(BookingController.class);

    private final BookingService bookingService;
    private final UserRepository userRepository;
    private final EmailService emailService;
    private final JdbcTemplate jdbcTemplate;

    public BookingController(BookingService bookingService, UserRepository userRepository,
                             EmailService emailService, JdbcTemplate jdbcTemplate) {
        this.bookingService = bookingService;
        this.userRepository = userRepository;
        this.emailService = emailService;
        this.jdbcTemplate = jdbcTemplate;
    }

    @GetMapping
    public ResponseEntity<List<BookingResponse>> listBookings(
        Authentication authentication,
        @RequestParam(value = "from", required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
        @RequestParam(value = "to", required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
        @RequestParam(value = "centerId", required = false) Long centerId,
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

        Long tenantFilter = null;

        if (authentication != null && authentication.isAuthenticated()) {
            Optional<User> userOpt = userRepository.findByEmail(authentication.getName());
            if (userOpt.isEmpty()) {
                return ResponseEntity.status(401).build();
            }
            User user = userOpt.get();
            if (user.getRole() == User.Role.ADMIN) {
                tenantFilter = tenantIdParam;
            } else {
                tenantFilter = user.getTenantId();
            }
        } else {
            return ResponseEntity.status(401).build();
        }

        List<BookingResponse> bookings = bookingService.getBookings(from, to, tenantFilter, centerId);
        return ResponseEntity.ok(bookings);
    }

    @PostMapping
    public ResponseEntity<?> createReserva(Authentication authentication, @Valid @RequestBody CreateReservaRequest request) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        Optional<User> userOpt = userRepository.findByEmail(authentication.getName());
        if (userOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        User user = userOpt.get();
        if (user.getRole() != User.Role.ADMIN) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        try {
            CreateReservaResponse response = bookingService.createReserva(request, user);
            return ResponseEntity.status(HttpStatus.CREATED).body(response);
        } catch (BookingConflictException conflictException) {
            Map<String, Object> body = new HashMap<>();
            body.put("message", conflictException.getMessage());
            body.put("conflicts", conflictException.getConflicts());
            return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
        } catch (IllegalArgumentException illegalArgumentException) {
            Map<String, Object> body = new HashMap<>();
            body.put("message", illegalArgumentException.getMessage());
            return ResponseEntity.badRequest().body(body);
        }
    }

    @PostMapping("/{id}/confirm-email")
    public ResponseEntity<Map<String, Object>> sendConfirmationEmail(
        Authentication authentication,
        @PathVariable Long id,
        @RequestParam(value = "testEmail", required = false) String testEmail
    ) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        Optional<User> userOpt = userRepository.findByEmail(authentication.getName());
        if (userOpt.isEmpty() || userOpt.get().getRole() != User.Role.ADMIN) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

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

            if (rows.isEmpty()) {
                return ResponseEntity.notFound().build();
            }

            Map<String, Object> row = rows.get(0);
            String email = (String) row.get("email");
            if (email == null || email.isBlank()) {
                Map<String, Object> error = new HashMap<>();
                error.put("error", "No email found for this booking's contact");
                return ResponseEntity.badRequest().body(error);
            }

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

            String dateStr = fechaIni != null ? fechaIni.format(dateFmt) : "—";
            String timeFromStr = fechaIni != null ? fechaIni.format(timeFmt) : "—";
            String timeToStr = fechaFin != null ? fechaFin.format(timeFmt) : "—";

            Double tarifa = row.get("tarifa") != null ? ((Number) row.get("tarifa")).doubleValue() : null;
            NumberFormat currencyFormat = NumberFormat.getCurrencyInstance(new Locale("es", "ES"));
            currencyFormat.setMinimumFractionDigits(2);

            String safeName = contactName != null ? contactName : "Cliente";
            String safeProduct = productoNombre != null ? productoNombre : "—";
            String safeCentro = centroNombre != null ? centroNombre : "—";

            String attendeesStr = attendees != null ? String.valueOf(attendees) : null;
            String tarifaStr = tarifa != null ? currencyFormat.format(tarifa) : null;

            String html = "<div style='font-family:Arial,Helvetica,sans-serif;max-width:600px;margin:0 auto;background:#ffffff;border-radius:12px;overflow:hidden;box-shadow:0 2px 12px rgba(0,0,0,0.08)'>"
                // ── Hero header with green gradient ──
                + "<div style='background:linear-gradient(135deg,#009624 0%,#00c853 100%);padding:40px 32px 32px;color:#ffffff'>"
                + "<p style='margin:0 0 4px;font-size:13px;letter-spacing:2px;text-transform:uppercase;opacity:0.85'>BEWORKING</p>"
                + "<h1 style='margin:0 0 8px;font-size:26px;font-weight:700;line-height:1.2'>Confirmaci\u00f3n de Reserva</h1>"
                + "</div>"
                // ── Body ──
                + "<div style='padding:32px'>"
                + "<p style='margin:0 0 8px;font-size:16px;color:#333'>Hola <strong>" + safeName + "</strong>, tu reserva ha sido confirmada.</p>"
                + "<p style='margin:0 0 24px;font-size:14px;color:#666'>A continuaci\u00f3n los detalles de tu reserva:</p>"
                // ── Details card ──
                + "<div style='background:#f5faf6;border-radius:10px;padding:20px 24px;border-left:4px solid #009624'>"
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
                + (tarifaStr != null ? "<tr><td style='padding:8px 12px 8px 0;color:#888;font-size:13px;white-space:nowrap'>Importe</td><td style='padding:8px 0;font-size:15px;font-weight:700;color:#009624'>" + tarifaStr + "</td></tr>" : "")
                + "</table>"
                + "</div>"
                // ── Info boxes (2 columns) ──
                + "<table style='border-collapse:collapse;width:100%;margin-top:28px'><tr>"
                + "<td style='width:50%;padding:0 8px 0 0;vertical-align:top'>"
                + "<div style='background:#f5faf6;border-radius:8px;padding:16px'>"
                + "<p style='margin:0 0 4px;font-size:14px;font-weight:700;color:#333'>Cambios o cancelaciones</p>"
                + "<p style='margin:0;font-size:12px;color:#888'>Cont\u00e1ctanos con antelaci\u00f3n para gestionar cualquier cambio.</p>"
                + "</div></td>"
                + "<td style='width:50%;padding:0 0 0 8px;vertical-align:top'>"
                + "<div style='background:#f5faf6;border-radius:8px;padding:16px'>"
                + "<p style='margin:0 0 4px;font-size:14px;font-weight:700;color:#333'>Acceso al centro</p>"
                + "<p style='margin:0;font-size:12px;color:#888'>Presenta este email en recepci\u00f3n a tu llegada.</p>"
                + "</div></td>"
                + "</tr></table>"
                // ── Contact line ──
                + "<p style='margin:28px 0 0;font-size:13px;color:#888;text-align:center'>"
                + "\u00bfNecesitas ayuda? Responde a este correo o escr\u00edbenos por WhatsApp: "
                + "<a href='https://wa.me/34640369759' style='color:#009624;text-decoration:none;font-weight:600'>+34 640 369 759</a></p>"
                + "</div>"
                // ── Footer ──
                + "<div style='background:#f9f9f9;padding:16px 32px;text-align:center;border-top:1px solid #eee'>"
                + "<p style='margin:0;font-size:12px;color:#aaa'>\u00a9 BeWorking \u00b7 M\u00e1laga</p>"
                + "</div>"
                + "</div>";

            String recipient = (testEmail != null && !testEmail.isBlank()) ? testEmail : email;
            emailService.sendHtml(recipient, "Confirmaci\u00f3n de reserva - BeWorking", html);

            Map<String, Object> response = new HashMap<>();
            response.put("message", "Confirmation email sent to " + recipient);
            response.put("bookingId", id);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            logger.error("Failed to send booking confirmation email for id {}: {}", id, e.getMessage(), e);
            Map<String, Object> error = new HashMap<>();
            error.put("error", "Failed to send confirmation email: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
        }
    }
}
