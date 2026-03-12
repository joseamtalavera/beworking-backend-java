package com.beworking.bookings;

import com.beworking.contacts.ContactProfileRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.UnexpectedRollbackException;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class PublicBookingControllerTest {

    @Mock private BookingService bookingService;
    @Mock private ContactProfileRepository contactRepository;
    @Mock private ProductoRepository productoRepository;
    @Mock private ReservaRepository reservaRepository;

    @InjectMocks
    private PublicBookingController controller;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    private PublicBookingRequest requestWithPI(String pi) {
        PublicBookingRequest req = new PublicBookingRequest();
        req.setFirstName("Gabriela");
        req.setLastName("Pardo Vidal");
        req.setEmail("gabrielapv0804@gmail.com");
        req.setProductName("MA1A2");
        req.setDate(java.time.LocalDate.of(2026, 3, 12));
        req.setStartTime("20:00");
        req.setEndTime("21:00");
        req.setStripePaymentIntentId(pi);
        return req;
    }

    // ── 1. Happy path ────────────────────────────────────────────────────────
    @Test
    void createPublicBooking_success_returns201() {
        CreateReservaResponse mockResponse = new CreateReservaResponse(1L, List.of());
        when(bookingService.createPublicBooking(any())).thenReturn(mockResponse);

        ResponseEntity<?> response = controller.createPublicBooking(requestWithPI("pi_abc"));

        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertSame(mockResponse, response.getBody());
    }

    // ── 2. Slot conflict → 409 ───────────────────────────────────────────────
    @Test
    void createPublicBooking_conflict_returns409() {
        when(bookingService.createPublicBooking(any()))
                .thenThrow(new BookingConflictException("Slot taken", List.of()));

        ResponseEntity<?> response = controller.createPublicBooking(requestWithPI("pi_conflict"));

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertNotNull(body);
        assertEquals("Slot taken", body.get("message"));
        assertEquals(true, body.get("refunded"));
    }

    // ── 3. Invalid argument → 400 ────────────────────────────────────────────
    @Test
    void createPublicBooking_illegalArgument_returns400() {
        when(bookingService.createPublicBooking(any()))
                .thenThrow(new IllegalArgumentException("Product not found: UNKNOWN"));

        ResponseEntity<?> response = controller.createPublicBooking(requestWithPI("pi_bad"));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertNotNull(body);
        assertTrue(body.get("message").toString().contains("Product not found"));
    }

    // ── 4. THE FIXED BUG: UnexpectedRollbackException → 500, no crash ────────
    //    This simulates what happened to Gabriela: booking transaction rolled back
    //    because invoice number generation corrupted the outer JPA session.
    @Test
    void createPublicBooking_unexpectedRollback_returns500WithPIReference() {
        String pi = "pi_3TA7xTIGBPwEtf1i1EQt0t5s";
        when(bookingService.createPublicBooking(any()))
                .thenThrow(new UnexpectedRollbackException("Transaction silently rolled back"));

        ResponseEntity<?> response = controller.createPublicBooking(requestWithPI(pi));

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertNotNull(body);
        assertTrue(body.get("message").toString().contains(pi),
                "Response should include the PI reference so the customer can be identified");
        assertEquals(false, body.get("refunded"),
                "Should NOT auto-refund: booking may have partially succeeded");
    }

    // ── 5. Any other RuntimeException → 500, no crash ────────────────────────
    @Test
    void createPublicBooking_runtimeException_returns500() {
        when(bookingService.createPublicBooking(any()))
                .thenThrow(new RuntimeException("DB connection lost"));

        ResponseEntity<?> response = controller.createPublicBooking(requestWithPI("pi_err"));

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
    }

    // ── 6. No PI provided and no conflict → no refund attempted ─────────────
    @Test
    void createPublicBooking_conflictWithNoPI_refundedFalse() {
        when(bookingService.createPublicBooking(any()))
                .thenThrow(new BookingConflictException("Conflict", List.of()));

        ResponseEntity<?> response = controller.createPublicBooking(requestWithPI(null));

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertEquals(false, body.get("refunded"));
    }
}
