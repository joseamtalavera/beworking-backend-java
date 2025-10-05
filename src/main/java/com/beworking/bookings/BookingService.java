package com.beworking.bookings;

import com.beworking.contacts.ContactProfile;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
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
            boolean applyFrom = from != null;
            boolean applyTo = to != null;
            LocalDate effectiveFrom = applyFrom ? from : LocalDate.now();
            LocalDate effectiveTo = applyTo ? to : LocalDate.now();

            List<Reserva> reservas = reservaRepository.findBookings(
                effectiveFrom,
                effectiveTo,
                tenantId,
                centerId,
                applyFrom,
                applyTo
            );

            List<BookingResponse> responses = new ArrayList<>();

            for (Reserva reserva : reservas) {
                List<Bloqueo> bloqueos = reserva.getBloqueos();
                if (bloqueos != null && !bloqueos.isEmpty()) {
                    bloqueos.stream()
                        .filter(bloqueo -> includeBlock(bloqueo, applyFrom, effectiveFrom, applyTo, effectiveTo))
                        .map(bloqueo -> mapToResponse(reserva, bloqueo))
                        .forEach(responses::add);
                } else {
                    responses.add(mapToResponse(reserva, null));
                }
            }

            responses.sort(responseComparator());
            return List.copyOf(responses);
        } catch (DataAccessException ex) {
            LOGGER.warn("Failed to load bookings", ex);
            return List.of();
        }
    }

    private static boolean includeBlock(Bloqueo bloqueo,
                                        boolean applyFrom,
                                        LocalDate from,
                                        boolean applyTo,
                                        LocalDate to) {
        LocalDate blockStart = bloqueo.getFechaIni() != null ? bloqueo.getFechaIni().toLocalDate() : null;
        LocalDate blockEnd = bloqueo.getFechaFin() != null ? bloqueo.getFechaFin().toLocalDate() : null;

        if (applyFrom && blockEnd != null && blockEnd.isBefore(from)) {
            return false;
        }
        if (applyTo && blockStart != null && blockStart.isAfter(to)) {
            return false;
        }
        return true;
    }

    private BookingResponse mapToResponse(Reserva reserva, Bloqueo bloqueo) {
        ContactProfile cliente = bloqueo != null && bloqueo.getCliente() != null
            ? bloqueo.getCliente()
            : reserva.getCliente();
        Centro centro = bloqueo != null && bloqueo.getCentro() != null
            ? bloqueo.getCentro()
            : reserva.getCentro();
        Producto producto = bloqueo != null && bloqueo.getProducto() != null
            ? bloqueo.getProducto()
            : reserva.getProducto();

        LocalDate dateFrom = bloqueo != null && bloqueo.getFechaIni() != null
            ? bloqueo.getFechaIni().toLocalDate()
            : reserva.getReservaDesde();
        LocalDate dateTo = bloqueo != null && bloqueo.getFechaFin() != null
            ? bloqueo.getFechaFin().toLocalDate()
            : reserva.getReservaHasta();

        String timeFrom = bloqueo != null ? formatTime(bloqueo.getFechaIni()) : normalizeTime(reserva.getReservaHoraDesde());
        String timeTo = bloqueo != null ? formatTime(bloqueo.getFechaFin()) : normalizeTime(reserva.getReservaHoraHasta());

        Double rate = bloqueo != null && bloqueo.getTarifa() != null ? bloqueo.getTarifa() : reserva.getTarifa();
        Integer attendees = bloqueo != null && bloqueo.getAsistentes() != null ? bloqueo.getAsistentes() : reserva.getAsistentes();
        String configuration = bloqueo != null && bloqueo.getConfiguracion() != null && !bloqueo.getConfiguracion().isBlank()
            ? bloqueo.getConfiguracion()
            : reserva.getConfiguracion();
        String notes = bloqueo != null && bloqueo.getNota() != null && !bloqueo.getNota().isBlank()
            ? bloqueo.getNota()
            : reserva.getNotas();
        String status = bloqueo != null && bloqueo.getEstado() != null ? bloqueo.getEstado() : reserva.getEstado();
        boolean openEnded = bloqueo != null ? isTrue(bloqueo.getFinIndefinido()) : isTrue(reserva.getFinIndefinido());
        List<String> days = bloqueo != null ? Collections.emptyList() : buildDays(reserva);

        LocalDateTime createdAt = bloqueo != null && bloqueo.getCreacionFecha() != null
            ? bloqueo.getCreacionFecha()
            : reserva.getCreacionFecha();
        LocalDateTime updatedAt = bloqueo != null && bloqueo.getEdicionFecha() != null
            ? bloqueo.getEdicionFecha()
            : reserva.getEdicionFecha();

        Long responseId = bloqueo != null ? bloqueo.getId() : reserva.getId();

        return new BookingResponse(
            responseId,
            cliente != null ? cliente.getId() : null,
            cliente != null ? cliente.getName() : null,
            cliente != null ? cliente.getEmailPrimary() : null,
            cliente != null ? cliente.getTenantType() : null,
            centro != null ? centro.getId() : null,
            centro != null ? centro.getCodigo() : null,
            centro != null ? centro.getNombre() : null,
            producto != null ? producto.getId() : null,
            producto != null ? producto.getNombre() : null,
            producto != null ? producto.getTipo() : null,
            reserva.getTipoReserva(),
            dateFrom,
            dateTo,
            timeFrom,
            timeTo,
            rate,
            attendees,
            configuration,
            notes,
            status,
            openEnded,
            days,
            createdAt,
            updatedAt
        );
    }

    private static Comparator<BookingResponse> responseComparator() {
        return (left, right) -> {
            LocalDate leftFrom = left.dateFrom();
            LocalDate rightFrom = right.dateFrom();

            if (leftFrom == null && rightFrom != null) {
                return 1;
            }
            if (leftFrom != null && rightFrom == null) {
                return -1;
            }
            if (leftFrom != null && rightFrom != null) {
                int compare = rightFrom.compareTo(leftFrom);
                if (compare != 0) {
                    return compare;
                }
            }

            String leftTime = Optional.ofNullable(left.timeFrom()).orElse("");
            String rightTime = Optional.ofNullable(right.timeFrom()).orElse("");
            int timeCompare = leftTime.compareTo(rightTime);
            if (timeCompare != 0) {
                return timeCompare;
            }

            return Long.compare(right.id(), left.id());
        };
    }

    private BookingResponse mapToResponse(Reserva reserva) {
        return mapToResponse(reserva, null);
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

    private static String formatTime(LocalDateTime value) {
        if (value == null) {
            return null;
        }
        int hour = value.getHour();
        int minute = value.getMinute();
        if (hour == 0 && minute == 0) {
            return null;
        }
        return String.format("%02d:%02d", hour, minute);
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
