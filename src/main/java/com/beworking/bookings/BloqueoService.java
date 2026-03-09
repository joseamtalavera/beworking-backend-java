package com.beworking.bookings;

import com.beworking.contacts.ContactProfile;
import com.beworking.contacts.ContactProfileRepository;
import jakarta.persistence.EntityNotFoundException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;

@Service
class BloqueoService {

    private static final Logger LOGGER = LoggerFactory.getLogger(BloqueoService.class);

    private final BloqueoRepository bloqueoRepository;
    private final ReservaRepository reservaRepository;
    private final ContactProfileRepository contactRepository;
    private final CentroRepository centroRepository;
    private final ProductoRepository productoRepository;
    private final JdbcTemplate jdbcTemplate;
    private final RestClient http;
    private final String paymentsBaseUrl;

    BloqueoService(BloqueoRepository bloqueoRepository,
                   ReservaRepository reservaRepository,
                   ContactProfileRepository contactRepository,
                   CentroRepository centroRepository,
                   ProductoRepository productoRepository,
                   JdbcTemplate jdbcTemplate,
                   @Value("${app.payments.base-url:}") String paymentsBaseUrl) {
        this.bloqueoRepository = bloqueoRepository;
        this.reservaRepository = reservaRepository;
        this.contactRepository = contactRepository;
        this.centroRepository = centroRepository;
        this.productoRepository = productoRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.http = RestClient.create();
        this.paymentsBaseUrl = paymentsBaseUrl;
    }

    @Transactional(readOnly = true)
    List<BloqueoResponse> getBloqueos(LocalDate from,
                                      LocalDate to,
                                      Long centerId,
                                      Long contactId,
                                      Long productId,
                                      Long tenantId) {
        LocalDateTime rangeStart = from != null ? from.atStartOfDay() : null;
        LocalDateTime rangeEndExclusive = to != null ? to.plusDays(1).atStartOfDay() : null;

        boolean applyFrom = rangeStart != null;
        boolean applyTo = rangeEndExclusive != null;

        try {
            List<Bloqueo> bloqueos = bloqueoRepository.findBloqueos(
                rangeStart,
                rangeEndExclusive,
                centerId,
                contactId,
                productId,
                tenantId,
                applyFrom,
                applyTo
            );
            return bloqueos.stream()
                .map(BloqueoMapper::toResponse)
                .toList();
        } catch (DataAccessException ex) {
            LOGGER.warn("Failed to load bloqueos", ex);
            return List.of();
        }
    }

    @Transactional(readOnly = true)
    List<BloqueoResponse> getUninvoicedBloqueos(Long contactId) {
        if (contactId == null) {
            return List.of();
        }
        try {
            List<Bloqueo> bloqueos = bloqueoRepository.findUninvoicedByContact(contactId);
            return bloqueos.stream()
                .map(BloqueoMapper::toResponse)
                .toList();
        } catch (DataAccessException ex) {
            LOGGER.warn("Failed to load uninvoiced bloqueos for contact {}", contactId, ex);
            return List.of();
        }
    }

    @Transactional
    BloqueoResponse updateBloqueo(Long id, UpdateBloqueoRequest request) {
        if (request.getDateFrom() == null || request.getDateTo() == null) {
            throw new IllegalArgumentException("Both dateFrom and dateTo are required");
        }
        if (request.getDateFrom().isAfter(request.getDateTo())) {
            throw new IllegalArgumentException("dateFrom must be on or before dateTo");
        }

        List<UpdateBloqueoRequest.TimeSlot> slots = Optional.ofNullable(request.getTimeSlots())
            .filter(list -> !list.isEmpty())
            .orElseThrow(() -> new IllegalArgumentException("At least one time slot is required"));
        UpdateBloqueoRequest.TimeSlot slot = slots.get(0);
        LocalTime startTime = parseTime(slot.getFrom());
        LocalTime endTime = parseTime(slot.getTo());
        if (!endTime.isAfter(startTime)) {
            throw new IllegalArgumentException("Time slot end must be after start time");
        }

        Bloqueo bloqueo = bloqueoRepository.findById(id)
            .orElseThrow(() -> new EntityNotFoundException("Bloqueo not found: " + id));

        ContactProfile cliente = contactRepository.findById(request.getContactId())
            .orElseThrow(() -> new IllegalArgumentException("Contact not found: " + request.getContactId()));
        Centro centro = centroRepository.findById(request.getCentroId())
            .orElseThrow(() -> new IllegalArgumentException("Centro not found: " + request.getCentroId()));
        Producto producto = productoRepository.findById(request.getProductoId())
            .orElseThrow(() -> new IllegalArgumentException("Producto not found: " + request.getProductoId()));

        LocalDateTime slotStart = request.getDateFrom().atTime(startTime);
        LocalDateTime slotEnd = request.getDateTo().atTime(endTime);

        List<Bloqueo> overlaps = bloqueoRepository.findOverlapping(producto.getId(), slotStart, slotEnd).stream()
            .filter(existing -> !existing.getId().equals(id))
            .toList();
        if (!overlaps.isEmpty()) {
            List<BookingConflictException.ConflictSlot> conflicts = overlaps.stream()
                .map(existing -> BookingConflictException.conflict(existing.getFechaIni(), existing.getFechaFin()))
                .toList();
            throw new BookingConflictException("Requested schedule overlaps with existing bloqueos", conflicts);
        }

        Integer openEndedFlag = Boolean.TRUE.equals(request.getOpenEnded()) ? 1 : 0;
        LocalDateTime now = LocalDateTime.now();

        bloqueo.setCliente(cliente);
        bloqueo.setCentro(centro);
        bloqueo.setProducto(producto);
        bloqueo.setFechaIni(slotStart);
        bloqueo.setFechaFin(slotEnd);
        bloqueo.setFinIndefinido(openEndedFlag);
        bloqueo.setTarifa(request.getTarifa());
        bloqueo.setAsistentes(request.getAttendees());
        bloqueo.setConfiguracion(request.getConfiguracion());
        bloqueo.setNota(request.getNote());
        bloqueo.setEstado(request.getStatus());
        bloqueo.setEdicionFecha(now);

        Reserva reserva = bloqueo.getReserva();
        if (reserva != null) {
            reserva.setCliente(cliente);
            reserva.setCentro(centro);
            reserva.setProducto(producto);
            reserva.setTipoReserva(request.getReservationType());
            reserva.setReservaDesde(request.getDateFrom());
            reserva.setReservaHasta(request.getDateTo());
            reserva.setFinIndefinido(openEndedFlag);
            Set<DayOfWeek> weekdays = normalizeWeekdays(request.getWeekdays());
            applyWeekdayFlags(reserva, weekdays);
            reserva.setReservaHoraDesde(formatHour(startTime));
            reserva.setReservaHoraHasta(formatHour(endTime));
            reserva.setTarifa(request.getTarifa());
            reserva.setAsistentes(request.getAttendees());
            reserva.setConfiguracion(request.getConfiguracion());
            reserva.setNotas(request.getNote());
            reserva.setEstado(request.getStatus());
            reserva.setEdicionFecha(now);
            reservaRepository.save(reserva);
        }

        Bloqueo saved = bloqueoRepository.save(bloqueo);
        return BloqueoMapper.toResponse(saved);
    }

    @Transactional
    void cancelFreeBloqueo(Long id, Long tenantId) {
        Bloqueo bloqueo = bloqueoRepository.findById(id)
            .orElseThrow(() -> new EntityNotFoundException("Bloqueo not found: " + id));

        // Only the owner can cancel
        if (bloqueo.getCliente() == null || !bloqueo.getCliente().getId().equals(tenantId)) {
            throw new SecurityException("Not authorized to cancel this booking");
        }

        // Only free bookings (tarifa == 0 or null, no Stripe payment data)
        Double tarifa = bloqueo.getTarifa();
        String nota = bloqueo.getNota();
        boolean isPaid = (tarifa != null && tarifa > 0)
            || (nota != null && nota.toLowerCase().contains("stripe"));
        if (isPaid) {
            throw new IllegalStateException("Only free bookings can be cancelled by the user");
        }

        deleteBloqueo(id);
    }

    @Transactional
    void deleteBloqueo(Long id) {
        // Void any linked unpaid Stripe invoices before deleting the bloqueo
        try {
            List<Map<String, Object>> linkedInvoices = jdbcTemplate.queryForList(
                """
                SELECT f.id, f.estado, f.stripeinvoiceid
                FROM beworking.facturasdesglose fd
                JOIN beworking.facturas f ON f.idfactura = fd.idfacturadesglose
                WHERE fd.idbloqueovinculado = ?
                """, id);

            for (Map<String, Object> invoice : linkedInvoices) {
                String estado = (String) invoice.get("estado");
                String stripeInvoiceId = (String) invoice.get("stripeinvoiceid");
                Long facturaId = ((Number) invoice.get("id")).longValue();

                boolean isPaid = estado != null && (
                    estado.equalsIgnoreCase("Pagado") || estado.equalsIgnoreCase("Paid"));

                // Void unpaid Stripe invoice
                if (!isPaid && stripeInvoiceId != null && !stripeInvoiceId.isBlank()
                        && paymentsBaseUrl != null && !paymentsBaseUrl.isBlank()) {
                    try {
                        http.post()
                            .uri(paymentsBaseUrl + "/api/void-invoice")
                            .header("Content-Type", "application/json")
                            .body(Map.of("invoice_id", stripeInvoiceId))
                            .retrieve()
                            .toBodilessEntity();
                        LOGGER.info("Voided Stripe invoice {} for bloqueo {}", stripeInvoiceId, id);
                    } catch (Exception ex) {
                        LOGGER.warn("Failed to void Stripe invoice {} for bloqueo {}: {}",
                            stripeInvoiceId, id, ex.getMessage());
                    }
                }

                // Mark local invoice as voided if not paid
                if (!isPaid) {
                    jdbcTemplate.update(
                        "UPDATE beworking.facturas SET estado = 'Anulado' WHERE id = ?", facturaId);
                    LOGGER.info("Marked factura {} as Anulado for deleted bloqueo {}", facturaId, id);
                }
            }

            // Clean up desglose records linking to this bloqueo
            jdbcTemplate.update(
                "DELETE FROM beworking.facturasdesglose WHERE idbloqueovinculado = ?", id);
        } catch (Exception ex) {
            LOGGER.warn("Invoice cleanup failed for bloqueo {}: {}", id, ex.getMessage());
        }

        try {
            bloqueoRepository.deleteById(id);
        } catch (EmptyResultDataAccessException ex) {
            LOGGER.warn("Attempted to delete non-existing bloqueo {}", id);
        }
    }

    private static Set<DayOfWeek> normalizeWeekdays(List<String> days) {
        if (days == null || days.isEmpty()) {
            return null;
        }
        EnumSet<DayOfWeek> set = EnumSet.noneOf(DayOfWeek.class);
        for (String value : days) {
            if (value == null) {
                continue;
            }
            String normalized = value.trim().toUpperCase();
            switch (normalized) {
                case "MONDAY", "LUNES" -> set.add(DayOfWeek.MONDAY);
                case "TUESDAY", "MARTES" -> set.add(DayOfWeek.TUESDAY);
                case "WEDNESDAY", "MIERCOLES", "MIÉRCOLES" -> set.add(DayOfWeek.WEDNESDAY);
                case "THURSDAY", "JUEVES" -> set.add(DayOfWeek.THURSDAY);
                case "FRIDAY", "VIERNES" -> set.add(DayOfWeek.FRIDAY);
                case "SATURDAY", "SABADO", "SÁBADO" -> set.add(DayOfWeek.SATURDAY);
                case "SUNDAY", "DOMINGO" -> set.add(DayOfWeek.SUNDAY);
                default -> LOGGER.debug("Ignoring unknown weekday token: {}", value);
            }
        }
        return set.isEmpty() ? null : set;
    }

    private static void applyWeekdayFlags(Reserva reserva, Set<DayOfWeek> days) {
        boolean includeAll = days == null || days.isEmpty();
        reserva.setLunes(includeAll || days.contains(DayOfWeek.MONDAY) ? 1 : 0);
        reserva.setMartes(includeAll || days.contains(DayOfWeek.TUESDAY) ? 1 : 0);
        reserva.setMiercoles(includeAll || days.contains(DayOfWeek.WEDNESDAY) ? 1 : 0);
        reserva.setJueves(includeAll || days.contains(DayOfWeek.THURSDAY) ? 1 : 0);
        reserva.setViernes(includeAll || days.contains(DayOfWeek.FRIDAY) ? 1 : 0);
        reserva.setSabado(includeAll || days.contains(DayOfWeek.SATURDAY) ? 1 : 0);
        reserva.setDomingo(includeAll || days.contains(DayOfWeek.SUNDAY) ? 1 : 0);
    }

    private static LocalTime parseTime(String value) {
        if (value == null) {
            throw new IllegalArgumentException("Time value is required");
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("Time value is required");
        }
        try {
            return LocalTime.parse(trimmed);
        } catch (DateTimeParseException ignored) {
            // continue
        }
        try {
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("H:mm");
            return LocalTime.parse(trimmed, formatter);
        } catch (DateTimeParseException ignored) {
            // continue
        }
        if (trimmed.matches("\\d{1,2}")) {
            int hour = Integer.parseInt(trimmed);
            return LocalTime.of(hour, 0);
        }
        throw new IllegalArgumentException("Invalid time value: " + value);
    }

    private static String formatHour(LocalTime time) {
        if (time == null) {
            return null;
        }
        return String.format("%02d:%02d", time.getHour(), time.getMinute());
    }
}
