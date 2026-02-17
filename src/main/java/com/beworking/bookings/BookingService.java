package com.beworking.bookings;

import com.beworking.auth.User;
import com.beworking.contacts.ContactProfile;
import com.beworking.contacts.ContactProfileRepository;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class BookingService {

    private static final Logger LOGGER = LoggerFactory.getLogger(BookingService.class);
    private static final String FREE_PRODUCT_NAME = "MA1A1";
    private static final String FREE_TENANT_TYPE = "Oficina Virtual";
    private static final int FREE_MONTHLY_LIMIT = 5;

    private final ReservaRepository reservaRepository;
    private final BloqueoRepository bloqueoRepository;
    private final ContactProfileRepository contactRepository;
    private final CentroRepository centroRepository;
    private final ProductoRepository productoRepository;

    BookingService(ReservaRepository reservaRepository,
                   BloqueoRepository bloqueoRepository,
                   ContactProfileRepository contactRepository,
                   CentroRepository centroRepository,
                   ProductoRepository productoRepository) {
        this.reservaRepository = reservaRepository;
        this.bloqueoRepository = bloqueoRepository;
        this.contactRepository = contactRepository;
        this.centroRepository = centroRepository;
        this.productoRepository = productoRepository;
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

    @Transactional
    CreateReservaResponse createPublicBooking(PublicBookingRequest request) {
        Producto producto = productoRepository.findByNombreIgnoreCase(request.getProductName())
            .orElseThrow(() -> new IllegalArgumentException("Product not found: " + request.getProductName()));

        Centro centro = centroRepository.findByCodigoIgnoreCase(producto.getCentroCodigo())
            .orElseThrow(() -> new IllegalArgumentException("Centro not found for product: " + request.getProductName()));

        String email = request.getEmail().trim().toLowerCase();
        ContactProfile contact = contactRepository
            .findFirstByEmailPrimaryIgnoreCaseOrEmailSecondaryIgnoreCaseOrEmailTertiaryIgnoreCaseOrRepresentativeEmailIgnoreCase(
                email, email, email, email)
            .orElseGet(() -> {
                ContactProfile newContact = new ContactProfile();
                Long nextId = contactRepository.findMaxId().map(v -> v + 1L).orElse(1L);
                newContact.setId(nextId);
                newContact.setName(request.getCompany() != null && !request.getCompany().isBlank()
                    ? request.getCompany()
                    : request.getFirstName() + " " + request.getLastName());
                newContact.setContactName(request.getFirstName() + " " + request.getLastName());
                newContact.setEmailPrimary(email);
                newContact.setPhonePrimary(request.getPhone());
                newContact.setRepresentativeFirstName(request.getFirstName());
                newContact.setRepresentativeLastName(request.getLastName());
                newContact.setRepresentativeEmail(email);
                newContact.setRepresentativePhone(request.getPhone());
                newContact.setBillingName(request.getCompany());
                newContact.setBillingTaxId(request.getTaxId());
                newContact.setBillingAddress(request.getBillingAddress());
                newContact.setBillingCity(request.getBillingCity());
                newContact.setBillingProvince(request.getBillingProvince());
                newContact.setBillingCountry(request.getBillingCountry());
                newContact.setBillingPostalCode(request.getBillingPostalCode());
                newContact.setTenantType("Por Horas");
                newContact.setStatus("Activo");
                newContact.setActive(true);
                newContact.setChannel("Web");
                newContact.setCreatedAt(java.time.LocalDateTime.now());
                return contactRepository.save(newContact);
            });

        CreateReservaRequest reservaRequest = new CreateReservaRequest();
        reservaRequest.setContactId(contact.getId());
        reservaRequest.setCentroId(centro.getId());
        reservaRequest.setProductoId(producto.getId());
        reservaRequest.setReservationType("Por Horas");
        reservaRequest.setDateFrom(request.getDate());
        reservaRequest.setDateTo(request.getDateTo() != null ? request.getDateTo() : request.getDate());
        reservaRequest.setStatus("Pendiente");
        reservaRequest.setAttendees(request.getAttendees());

        CreateReservaRequest.TimeSlot slot = new CreateReservaRequest.TimeSlot();
        slot.setFrom(request.getStartTime());
        slot.setTo(request.getEndTime());
        reservaRequest.setTimeSlots(List.of(slot));

        String note = null;
        boolean isFreeEligible = false;

        if (FREE_PRODUCT_NAME.equalsIgnoreCase(producto.getNombre())
                && FREE_TENANT_TYPE.equalsIgnoreCase(contact.getTenantType())) {
            YearMonth currentMonth = YearMonth.now();
            LocalDateTime monthStart = currentMonth.atDay(1).atStartOfDay();
            LocalDateTime monthEnd = currentMonth.plusMonths(1).atDay(1).atStartOfDay();
            long usedThisMonth = reservaRepository.countByContactAndProductInMonth(
                contact.getId(), producto.getId(), monthStart, monthEnd);

            if (usedThisMonth < FREE_MONTHLY_LIMIT) {
                isFreeEligible = true;
                note = "Free booking (" + (usedThisMonth + 1) + " of " + FREE_MONTHLY_LIMIT + ")";
                reservaRequest.setStatus("Paid");
            }
        }

        if (!isFreeEligible) {
            if (request.getStripeSubscriptionId() != null) {
                note = "Stripe Sub: " + request.getStripeSubscriptionId();
                if (request.getStripePaymentIntentId() != null) {
                    note += " | PI: " + request.getStripePaymentIntentId();
                }
            } else if (request.getStripePaymentIntentId() != null) {
                note = "Stripe PI: " + request.getStripePaymentIntentId();
            }
        }

        reservaRequest.setNote(note);

        return createReserva(reservaRequest, null);
    }

    @Transactional
    CreateReservaResponse createReserva(CreateReservaRequest request, User user) {
        if (request.getDateFrom().isAfter(request.getDateTo())) {
            throw new IllegalArgumentException("dateFrom must be on or before dateTo");
        }
        if (request.getTimeSlots() == null || request.getTimeSlots().isEmpty()) {
            throw new IllegalArgumentException("At least one time slot is required");
        }

        ContactProfile cliente = contactRepository.findById(request.getContactId())
            .orElseThrow(() -> new IllegalArgumentException("Contact not found: " + request.getContactId()));
        Centro centro = centroRepository.findById(request.getCentroId())
            .orElseThrow(() -> new IllegalArgumentException("Centro not found: " + request.getCentroId()));
        Producto producto = productoRepository.findById(request.getProductoId())
            .orElseThrow(() -> new IllegalArgumentException("Producto not found: " + request.getProductoId()));

        Set<DayOfWeek> weekdays = normalizeWeekdays(request.getWeekdays());
        LocalDate startDate = request.getDateFrom();
        LocalDate endDate = request.getDateTo();

        LocalDateTime now = LocalDateTime.now();
        List<Bloqueo> bloqueosToPersist = new ArrayList<>();
        List<BookingConflictException.ConflictSlot> conflicts = new ArrayList<>();

        LocalDate cursor = startDate;
        while (!cursor.isAfter(endDate)) {
            if (weekdays == null || weekdays.contains(cursor.getDayOfWeek())) {
                for (CreateReservaRequest.TimeSlot slot : request.getTimeSlots()) {
                    LocalTime startTime = parseTime(slot.getFrom());
                    LocalTime endTime = parseTime(slot.getTo());
                    LocalDateTime slotStart = cursor.atTime(startTime);
                    LocalDateTime slotEnd = cursor.atTime(endTime);
                    if (!slotEnd.isAfter(slotStart)) {
                        throw new IllegalArgumentException("Time slot end must be after start");
                    }

                    List<Bloqueo> overlaps = bloqueoRepository.findOverlapping(producto.getId(), slotStart, slotEnd);
                    if (!overlaps.isEmpty()) {
                        overlaps.stream()
                            .map(existing -> BookingConflictException.conflict(existing.getFechaIni(), existing.getFechaFin()))
                            .forEach(conflicts::add);
                    } else {
                        Bloqueo bloqueo = new Bloqueo();
                        bloqueo.setCliente(cliente);
                        bloqueo.setCentro(centro);
                        bloqueo.setProducto(producto);
                        bloqueo.setFechaIni(slotStart);
                        bloqueo.setFechaFin(slotEnd);
                        bloqueo.setFinIndefinido(Boolean.TRUE.equals(request.getOpenEnded()) ? 1 : 0);
                        bloqueo.setTarifa(request.getTarifa());
                        bloqueo.setAsistentes(request.getAttendees());
                        bloqueo.setConfiguracion(request.getConfiguracion());
                        bloqueo.setNota(request.getNote());
                        bloqueo.setEstado(request.getStatus());
                        bloqueo.setCreacionFecha(now);
                        bloqueo.setEdicionFecha(now);
                        bloqueosToPersist.add(bloqueo);
                    }
                }
            }
            cursor = cursor.plusDays(1);
        }

        if (!conflicts.isEmpty()) {
            String conflictSummary = conflicts.stream()
                .map(slot -> String.format("[%s - %s]", slot.start(), slot.end()))
                .reduce((left, right) -> left + ", " + right)
                .orElse("");
            LOGGER.warn("Reserva conflict for contact {} (centro {}, producto {}) between {} and {}. Conflicts: {}",
                cliente.getId(),
                centro.getId(),
                producto.getId(),
                startDate,
                endDate,
                conflictSummary
            );
            throw new BookingConflictException("Requested schedule overlaps with existing bloqueos", conflicts);
        }

        if (bloqueosToPersist.isEmpty()) {
            throw new IllegalArgumentException("Unable to derive bloqueos for the supplied schedule");
        }

        Reserva reserva = new Reserva();
        reserva.setCliente(cliente);
        reserva.setCentro(centro);
        reserva.setProducto(producto);
        reserva.setTipoReserva(request.getReservationType());
        reserva.setReservaDesde(startDate);
        reserva.setReservaHasta(endDate);
        reserva.setFinIndefinido(Boolean.TRUE.equals(request.getOpenEnded()) ? 1 : 0);
        applyWeekdayFlags(reserva, weekdays);

        LocalTime primaryStart = parseTime(request.getTimeSlots().get(0).getFrom());
        LocalTime primaryEnd = parseTime(request.getTimeSlots().get(0).getTo());
        reserva.setReservaHoraDesde(formatHour(primaryStart));
        reserva.setReservaHoraHasta(formatHour(primaryEnd));
        reserva.setTarifa(request.getTarifa());
        reserva.setAsistentes(request.getAttendees());
        reserva.setConfiguracion(request.getConfiguracion());
        reserva.setNotas(request.getNote());
        reserva.setEstado(request.getStatus());
        reserva.setCreacionFecha(now);
        reserva.setEdicionFecha(now);
        if (user != null && user.getId() != null) {
            reserva.setUsuarioId(Math.toIntExact(user.getId()));
        }

        Long nextReservaId = reservaRepository.findMaxId().map(value -> value + 1L).orElse(1L);
        reserva.setId(nextReservaId);

        Reserva savedReserva = reservaRepository.save(reserva);

        long[] nextBloqueoId = {bloqueoRepository.findMaxId().map(value -> value + 1L).orElse(1L)};

        bloqueosToPersist.forEach(bloqueo -> {
            bloqueo.setId(nextBloqueoId[0]++);
            bloqueo.setReserva(savedReserva);
            bloqueo.setCliente(cliente);
            bloqueo.setCentro(centro);
            bloqueo.setProducto(producto);
        });

        List<Bloqueo> savedBloqueos = bloqueoRepository.saveAll(bloqueosToPersist);
        savedReserva.setBloqueos(savedBloqueos);

        List<BloqueoResponse> bloqueoResponses = savedBloqueos.stream()
            .map(BloqueoMapper::toResponse)
            .toList();

        return new CreateReservaResponse(savedReserva.getId(), bloqueoResponses);
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
