package com.beworking.bookings;

import com.beworking.contacts.ContactProfile;
import com.beworking.contacts.ContactProfileRepository;
import jakarta.validation.Valid;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpEntity;
import org.springframework.http.MediaType;

@RestController
@RequestMapping("/api/public")
public class PublicBookingController {

    private static final Logger LOGGER = LoggerFactory.getLogger(PublicBookingController.class);
    private static final String FREE_PRODUCT_NAME = "MA1A1";
    private static final String FREE_TENANT_TYPE_VIRTUAL = "Usuario Virtual";
    private static final String FREE_TENANT_TYPE_DESK = "Usuario Mesa";
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
            tryRefundPaymentIntent(request.getStripePaymentIntentId(), "slot conflict");
            Map<String, Object> body = new HashMap<>();
            body.put("message", conflictException.getMessage());
            body.put("conflicts", conflictException.getConflicts());
            body.put("refunded", request.getStripePaymentIntentId() != null);
            return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
        } catch (IllegalArgumentException illegalArgumentException) {
            tryRefundPaymentIntent(request.getStripePaymentIntentId(), illegalArgumentException.getMessage());
            Map<String, Object> body = new HashMap<>();
            body.put("message", illegalArgumentException.getMessage());
            body.put("refunded", request.getStripePaymentIntentId() != null);
            return ResponseEntity.badRequest().body(body);
        }
    }

    private void tryRefundPaymentIntent(String paymentIntentId, String reason) {
        if (paymentIntentId == null || paymentIntentId.isBlank()) return;
        try {
            String stripeServiceUrl = System.getenv("STRIPE_SERVICE_URL") != null
                ? System.getenv("STRIPE_SERVICE_URL")
                : "http://beworking-stripe-service:8081";
            RestTemplate restTemplate = new RestTemplate();
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            String body = String.format("{\"payment_intent_id\":\"%s\"}", paymentIntentId);
            HttpEntity<String> entity = new HttpEntity<>(body, headers);
            restTemplate.postForObject(stripeServiceUrl + "/api/refunds", entity, Map.class);
            LOGGER.info("Auto-refunded payment intent {} due to: {}", paymentIntentId, reason);
        } catch (Exception e) {
            LOGGER.error("Failed to auto-refund payment intent {} — manual refund required. Reason: {}", paymentIntentId, e.getMessage());
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

        // Must be Usuario Virtual or Usuario Mesa
        String normalizedEmail = email.trim().toLowerCase();
        ContactProfile contact = contactRepository
            .findFirstByEmailPrimaryIgnoreCaseOrEmailSecondaryIgnoreCaseOrEmailTertiaryIgnoreCaseOrRepresentativeEmailIgnoreCase(
                normalizedEmail, normalizedEmail, normalizedEmail, normalizedEmail)
            .orElse(null);

        if (contact == null || contact.getTenantType() == null) {
            body.put("used", 0);
            body.put("freeLimit", 0);
            body.put("isFree", false);
            return ResponseEntity.ok(body);
        }

        String tenantType = contact.getTenantType();

        // Desk users: unlimited free bookings in MA1A1
        if (FREE_TENANT_TYPE_DESK.equalsIgnoreCase(tenantType)) {
            body.put("used", 0);
            body.put("freeLimit", -1);
            body.put("isFree", true);
            body.put("unlimited", true);
            return ResponseEntity.ok(body);
        }

        // Virtual office users: 5 free bookings per month in MA1A1
        if (!FREE_TENANT_TYPE_VIRTUAL.equalsIgnoreCase(tenantType)) {
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
