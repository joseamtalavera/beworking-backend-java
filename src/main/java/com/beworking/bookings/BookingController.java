package com.beworking.bookings;

import com.beworking.auth.User;
import com.beworking.auth.UserRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    private final BookingService bookingService;
    private final UserRepository userRepository;

    public BookingController(BookingService bookingService, UserRepository userRepository) {
        this.bookingService = bookingService;
        this.userRepository = userRepository;
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
}
