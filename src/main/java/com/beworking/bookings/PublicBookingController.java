package com.beworking.bookings;

import jakarta.validation.Valid;
import java.util.HashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/public/bookings")
public class PublicBookingController {

    private final BookingService bookingService;

    public PublicBookingController(BookingService bookingService) {
        this.bookingService = bookingService;
    }

    @PostMapping
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
}
