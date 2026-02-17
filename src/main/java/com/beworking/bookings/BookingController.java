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

            String html = "<div style='font-family:Arial,sans-serif;max-width:600px;margin:0 auto'>"
                + "<div style='background:#009624;padding:16px;text-align:center'>"
                + "<img src='https://app.be-working.com/beworking_logo.png' alt='BeWorking' style='height:40px' />"
                + "</div>"
                + "<div style='padding:24px'>"
                + "<p>Estimado/a " + safeName + ",</p>"
                + "<p>Su reserva ha sido confirmada.</p>"
                + "<h3 style='color:#009624;margin-top:24px'>Detalles de la reserva</h3>"
                + "<table style='border-collapse:collapse'>"
                + "<tr><td style='padding:4px 12px 4px 0;color:#666'>Sala:</td><td><strong>" + safeProduct + "</strong></td></tr>"
                + "<tr><td style='padding:4px 12px 4px 0;color:#666'>Centro:</td><td>" + safeCentro + "</td></tr>"
                + "<tr><td style='padding:4px 12px 4px 0;color:#666'>Fecha:</td><td>" + dateStr + "</td></tr>"
                + "<tr><td style='padding:4px 12px 4px 0;color:#666'>Horario:</td><td>" + timeFromStr + " - " + timeToStr + "</td></tr>"
                + (attendees != null ? "<tr><td style='padding:4px 12px 4px 0;color:#666'>Asistentes:</td><td>" + attendees + "</td></tr>" : "")
                + (tarifa != null ? "<tr><td style='padding:4px 12px 4px 0;color:#666'>Importe:</td><td>" + currencyFormat.format(tarifa) + "</td></tr>" : "")
                + "</table>"
                + "<p style='margin-top:24px;color:#666;font-size:13px'>Gracias por ser parte de la comunidad BeWorking.</p>"
                + "</div></div>";

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
