package com.beworking.bookings;

import com.beworking.contacts.ContactProfile;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class BookingService {

    private static final Logger LOGGER = LoggerFactory.getLogger(BookingService.class);

    private final ReservaRepository reservaRepository;

    BookingService(ReservaRepository reservaRepository) {
        this.reservaRepository = reservaRepository;
    }

    @Transactional(readOnly = true)
    List<BookingResponse> getBookings(LocalDate from, LocalDate to, Long tenantId, Long centerId) {
        try {
            List<Reserva> reservas = reservaRepository.findBookings(from, to, tenantId, centerId);
            return reservas.stream().map(this::mapToResponse).toList();
        } catch (DataAccessException ex) {
            LOGGER.warn("Failed to load bookings", ex);
            return List.of();
        }
    }

    private BookingResponse mapToResponse(Reserva reserva) {
        ContactProfile cliente = reserva.getCliente();
        Centro centro = reserva.getCentro();
        Producto producto = reserva.getProducto();

        return new BookingResponse(
            reserva.getId(),
            cliente != null ? cliente.getId() : null,
            cliente != null ? cliente.getName() : null,
            cliente != null ? cliente.getEmailPrimary() : null,
            centro != null ? centro.getId() : null,
            centro != null ? centro.getCodigo() : null,
            centro != null ? centro.getNombre() : null,
            producto != null ? producto.getId() : null,
            producto != null ? producto.getNombre() : null,
            producto != null ? producto.getTipo() : null,
            reserva.getTipoReserva(),
            reserva.getReservaDesde(),
            reserva.getReservaHasta(),
            normalizeTime(reserva.getReservaHoraDesde()),
            normalizeTime(reserva.getReservaHoraHasta()),
            reserva.getTarifa(),
            reserva.getAsistentes(),
            reserva.getConfiguracion(),
            reserva.getNotas(),
            reserva.getEstado(),
            isTrue(reserva.getFinIndefinido()),
            buildDays(reserva),
            reserva.getCreacionFecha(),
            reserva.getEdicionFecha()
        );
    }

    private static boolean isTrue(Integer value) {
        return value != null && value.intValue() == 1;
    }

    private static List<String> buildDays(Reserva reserva) {
        List<String> days = new ArrayList<>(7);
        if (isTrue(reserva.getLunes())) {
            days.add("monday");
        }
        if (isTrue(reserva.getMartes())) {
            days.add("tuesday");
        }
        if (isTrue(reserva.getMiercoles())) {
            days.add("wednesday");
        }
        if (isTrue(reserva.getJueves())) {
            days.add("thursday");
        }
        if (isTrue(reserva.getViernes())) {
            days.add("friday");
        }
        if (isTrue(reserva.getSabado())) {
            days.add("saturday");
        }
        if (isTrue(reserva.getDomingo())) {
            days.add("sunday");
        }
        return List.copyOf(days);
    }

    private static String normalizeTime(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        String candidate = trimmed.replace('.', ':');
        String[] parts = candidate.split(":");
        try {
            int hour = Integer.parseInt(parts[0]);
            int minute = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
            if (hour < 0) {
                hour = 0;
            }
            if (minute < 0) {
                minute = 0;
            }
            return String.format("%02d:%02d", hour, minute);
        } catch (NumberFormatException ex) {
            return trimmed;
        }
    }
}
