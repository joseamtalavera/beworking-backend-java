package com.beworking.bookings;

import com.beworking.contacts.ContactProfile;
import com.beworking.contacts.ContactProfileRepository;
import jakarta.validation.Valid;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.HashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/public")
public class PublicBookingController {

    private static final String FREE_PRODUCT_NAME = "MA1A1";
    private static final String FREE_TENANT_TYPE = "Oficina Virtual";
    private static final int FREE_MONTHLY_LIMIT = 5;

    private final BookingService bookingService;
    private final ContactProfileRepository contactRepository;
    private final ProductoRepository productoRepository;
    private final ReservaRepository reservaRepository;

    public PublicBookingController(BookingService bookingService,
                                   ContactProfileRepository contactRepository,
                                   ProductoRepository productoRepository,
                                   ReservaRepository reservaRepository) {
        this.bookingService = bookingService;
        this.contactRepository = contactRepository;
        this.productoRepository = productoRepository;
        this.reservaRepository = reservaRepository;
    }

    @PostMapping("/bookings")
    public ResponseEntity<?> createPublicBooking(@Valid @RequestBody PublicBookingRequest request) {
        try {
            CreateReservaResponse response = bookingService.createPublicBooking(request);
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

    @GetMapping("/booking-usage")
    public ResponseEntity<?> getBookingUsage(@RequestParam String email,
                                             @RequestParam String productName) {
        Map<String, Object> body = new HashMap<>();

        // Must be product MA1A1
        if (!FREE_PRODUCT_NAME.equalsIgnoreCase(productName)) {
            body.put("used", 0);
            body.put("freeLimit", 0);
            body.put("isFree", false);
            return ResponseEntity.ok(body);
        }

        // Must be Oficina Virtual contact
        String normalizedEmail = email.trim().toLowerCase();
        ContactProfile contact = contactRepository
            .findFirstByEmailPrimaryIgnoreCaseOrEmailSecondaryIgnoreCaseOrEmailTertiaryIgnoreCaseOrRepresentativeEmailIgnoreCase(
                normalizedEmail, normalizedEmail, normalizedEmail, normalizedEmail)
            .orElse(null);

        if (contact == null || contact.getTenantType() == null
                || !FREE_TENANT_TYPE.equalsIgnoreCase(contact.getTenantType())) {
            body.put("used", 0);
            body.put("freeLimit", 0);
            body.put("isFree", false);
            return ResponseEntity.ok(body);
        }

        Producto producto = productoRepository.findByNombreIgnoreCase(productName).orElse(null);
        if (producto == null) {
            body.put("used", 0);
            body.put("freeLimit", FREE_MONTHLY_LIMIT);
            body.put("isFree", true);
            return ResponseEntity.ok(body);
        }

        YearMonth currentMonth = YearMonth.now();
        LocalDateTime monthStart = currentMonth.atDay(1).atStartOfDay();
        LocalDateTime monthEnd = currentMonth.plusMonths(1).atDay(1).atStartOfDay();

        long used = reservaRepository.countByContactAndProductInMonth(
            contact.getId(), producto.getId(), monthStart, monthEnd);

        body.put("used", used);
        body.put("freeLimit", FREE_MONTHLY_LIMIT);
        body.put("isFree", used < FREE_MONTHLY_LIMIT);
        return ResponseEntity.ok(body);
    }
}
