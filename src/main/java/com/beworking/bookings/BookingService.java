package com.beworking.bookings;

import com.beworking.auth.User;
import com.beworking.auth.RegisterService;
import com.beworking.contacts.ContactProfile;
import com.beworking.contacts.ContactProfileRepository;
import com.beworking.auth.EmailService;
import com.beworking.cuentas.CuentaService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.Duration;
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
import org.springframework.dao.EmptyResultDataAccessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
class BookingService {

    private static final Logger LOGGER = LoggerFactory.getLogger(BookingService.class);
    private static final String FREE_PRODUCT_NAME = "MA1A1";
    private static final String FREE_TENANT_TYPE_VIRTUAL = "Usuario Virtual";
    private static final String FREE_TENANT_TYPE_DESK = "Usuario Mesa";
    private static final int FREE_MONTHLY_LIMIT = 5;

    private static final Set<String> EU_VAT_PREFIXES = Set.of(
        "AT", "BE", "BG", "CY", "CZ", "DE", "DK", "EE", "ES", "FI", "FR",
        "EL", "HR", "HU", "IE", "IT", "LT", "LU", "LV", "MT", "NL", "PL",
        "PT", "RO", "SE", "SI", "SK"
    );

    @PersistenceContext
    private EntityManager entityManager;

    private final ReservaRepository reservaRepository;
    private final BloqueoRepository bloqueoRepository;
    private final ContactProfileRepository contactRepository;
    private final CentroRepository centroRepository;
    private final ProductoRepository productoRepository;
    private final EmailService emailService;
    private final JdbcTemplate jdbcTemplate;
    private final CuentaService cuentaService;
    private final RegisterService registerService;
    private final com.beworking.contacts.ContactProfileService contactProfileService;

    BookingService(ReservaRepository reservaRepository,
                   BloqueoRepository bloqueoRepository,
                   ContactProfileRepository contactRepository,
                   CentroRepository centroRepository,
                   ProductoRepository productoRepository,
                   EmailService emailService,
                   JdbcTemplate jdbcTemplate,
                   CuentaService cuentaService,
                   RegisterService registerService,
                   @org.springframework.context.annotation.Lazy com.beworking.contacts.ContactProfileService contactProfileService) {
        this.reservaRepository = reservaRepository;
        this.bloqueoRepository = bloqueoRepository;
        this.contactRepository = contactRepository;
        this.centroRepository = centroRepository;
        this.productoRepository = productoRepository;
        this.emailService = emailService;
        this.jdbcTemplate = jdbcTemplate;
        this.cuentaService = cuentaService;
        this.registerService = registerService;
        this.contactProfileService = contactProfileService;
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
                newContact.setTenantType("Usuario Aulas");
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
        if (request.getWeekdays() != null && !request.getWeekdays().isEmpty()) {
            reservaRequest.setWeekdays(request.getWeekdays());
        }

        CreateReservaRequest.TimeSlot slot = new CreateReservaRequest.TimeSlot();
        slot.setFrom(request.getStartTime());
        slot.setTo(request.getEndTime());
        reservaRequest.setTimeSlots(List.of(slot));

        String note = null;
        boolean isFreeEligible = false;

        if (FREE_PRODUCT_NAME.equalsIgnoreCase(producto.getNombre())) {
            String tenantType = contact.getTenantType();
            if (FREE_TENANT_TYPE_DESK.equalsIgnoreCase(tenantType)) {
                isFreeEligible = true;
                note = "Free booking (desk user)";
                reservaRequest.setStatus("Free");
            } else if (FREE_TENANT_TYPE_VIRTUAL.equalsIgnoreCase(tenantType)) {
                YearMonth currentMonth = YearMonth.now();
                LocalDateTime monthStart = currentMonth.atDay(1).atStartOfDay();
                LocalDateTime monthEnd = currentMonth.plusMonths(1).atDay(1).atStartOfDay();
                long usedThisMonth = reservaRepository.countByContactAndProductInMonth(
                    contact.getId(), producto.getId(), monthStart, monthEnd);

                if (usedThisMonth < FREE_MONTHLY_LIMIT) {
                    isFreeEligible = true;
                    note = "Free booking (" + (usedThisMonth + 1) + " of " + FREE_MONTHLY_LIMIT + ")";
                    reservaRequest.setStatus("Free");
                }
            }
        }

        if (!isFreeEligible) {
            if (request.getStripeSubscriptionId() != null) {
                note = "Stripe Sub: " + request.getStripeSubscriptionId();
                if (request.getStripePaymentIntentId() != null) {
                    note += " | PI: " + request.getStripePaymentIntentId();
                }
                reservaRequest.setStatus("Pagado");
            } else if (request.getStripePaymentIntentId() != null) {
                note = "Stripe PI: " + request.getStripePaymentIntentId();
                reservaRequest.setStatus("Pagado");
            }
        }

        reservaRequest.setNote(note);

        CreateReservaResponse response = createReserva(reservaRequest, null);

        // Flush pending Hibernate INSERTs so jdbcTemplate invoice queries see the new bloqueo
        entityManager.flush();

        // ── Auto-create invoice for paid public bookings ──
        boolean isPaid = request.getStripePaymentIntentId() != null && !isFreeEligible;
        if (isPaid && response.bloqueos() != null && !response.bloqueos().isEmpty()) {
            try {
                List<BloqueoResponse> allBloqueos = response.bloqueos();
                int sessionCount = allBloqueos.size();

                // Calculate price from room hourly rate × hours × sessions
                BigDecimal hourlyRate = jdbcTemplate.queryForObject(
                    "SELECT price_from FROM beworking.rooms WHERE code = ?",
                    BigDecimal.class, producto.getNombre());

                if (hourlyRate != null) {
                    LocalTime startTime = LocalTime.parse(request.getStartTime());
                    LocalTime endTime = LocalTime.parse(request.getEndTime());
                    long minutes = Duration.between(startTime, endTime).toMinutes();
                    BigDecimal hours = new BigDecimal(minutes).divide(new BigDecimal("60"), 4, RoundingMode.HALF_UP);
                    BigDecimal perSession = hourlyRate.multiply(hours).setScale(2, RoundingMode.HALF_UP);
                    BigDecimal subtotal = perSession.multiply(BigDecimal.valueOf(sessionCount)).setScale(2, RoundingMode.HALF_UP);

                    // Resolve cuenta and VAT rate per contact
                    String cuentaCodigo = resolveContactCuenta(contact.getId());
                    int vatPercent = resolveContactVatPercent(contact.getId(), cuentaCodigo);
                    BigDecimal vatRate = BigDecimal.valueOf(vatPercent).divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP);
                    BigDecimal vat = subtotal.multiply(vatRate).setScale(2, RoundingMode.HALF_UP);
                    BigDecimal total = subtotal.add(vat).setScale(2, RoundingMode.HALF_UP);

                    String invoiceNumber = cuentaService.generateNextInvoiceNumber(cuentaCodigo);
                    String numericPart = invoiceNumber.replaceAll("[^0-9]", "");
                    Integer invoiceId = numericPart.isEmpty() ? 0 : Integer.parseInt(numericPart);

                    Integer cuentaId = jdbcTemplate.queryForObject(
                        "SELECT id FROM beworking.cuentas WHERE codigo = ?", Integer.class, cuentaCodigo);

                    Long nextId = jdbcTemplate.queryForObject(
                        "SELECT nextval('beworking.facturas_id_seq')", Long.class);

                    String dateRange = sessionCount > 1
                        ? request.getDate() + " — " + request.getDateTo()
                        : String.valueOf(request.getDate());

                    jdbcTemplate.update("""
                        INSERT INTO beworking.facturas (
                            id, idfactura, idcliente, idcentro, holdedcuenta, id_cuenta,
                            descripcion, holdedinvoicenum,
                            fechacreacionreal, estado,
                            total, iva, totaliva, notas, creacionfecha,
                            stripepaymentintentid1, stripepaymentintentstatus1
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'Pagado', ?, ?, ?, ?, CURRENT_TIMESTAMP, ?, 'succeeded')
                        """,
                        nextId,
                        invoiceId,
                        contact.getId(),
                        centro.getId(),
                        cuentaCodigo,
                        cuentaId,
                        "Reserva: " + producto.getNombre() + " (" + dateRange + ")",
                        invoiceNumber,
                        java.sql.Timestamp.valueOf(request.getDate().atStartOfDay()),
                        total,
                        vatPercent,
                        vat,
                        note,
                        request.getStripePaymentIntentId()
                    );

                    // Insert line items — one per session
                    for (BloqueoResponse bi : allBloqueos) {
                        Long nextDesgloseId = jdbcTemplate.queryForObject(
                            "SELECT nextval('beworking.facturasdesglose_id_seq')", Long.class);

                        String bloqueoDate = bi.fechaIni() != null
                            ? bi.fechaIni().toLocalDate().toString()
                            : request.getDate().toString();

                        jdbcTemplate.update("""
                            INSERT INTO beworking.facturasdesglose (
                                id, idfacturadesglose, conceptodesglose, precioundesglose,
                                cantidaddesglose, totaldesglose, desgloseconfirmado, idbloqueovinculado, factura_id
                            ) VALUES (?, ?, ?, ?, ?, ?, 1, ?, ?)
                            """,
                            nextDesgloseId,
                            invoiceId,
                            "Reserva " + producto.getNombre() + " " + bloqueoDate + " " + request.getStartTime() + "-" + request.getEndTime(),
                            perSession,
                            BigDecimal.ONE,
                            perSession,
                            bi.id(),
                            nextId
                        );

                        // Update bloqueo status
                        jdbcTemplate.update(
                            "UPDATE beworking.bloqueos SET estado = 'Pagado' WHERE id = ?", bi.id());
                    }

                    LOGGER.info("Auto-created invoice {} for {} public booking sessions", invoiceNumber, sessionCount);
                }
            } catch (Exception e) {
                LOGGER.error("Failed to auto-create invoice for public booking — payment was collected but invoice record is missing!", e);
                throw new RuntimeException("Invoice creation failed after payment. PaymentIntent: " + request.getStripePaymentIntentId(), e);
            }
        }

        // ── Send emails and auto-create user account only after transaction commits ──
        final String finalNote = note;
        final String contactEmail = contact.getEmailPrimary();
        final String contactName = contact.getContactName() != null ? contact.getContactName() : contact.getName();
        final Long contactId = contact.getId();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                sendBookingEmails(request, reservaRequest.getStatus(), finalNote);
                try {
                    registerService.createUserForBookingContact(contactEmail, contactName, contactId);
                } catch (Exception e) {
                    LOGGER.warn("Failed to auto-create user account for booking contact {}: {}", contactEmail, e.getMessage());
                }
            }
        });

        return response;
    }

    private String resolveContactCuenta(Long contactId) {
        try {
            String cuenta = jdbcTemplate.queryForObject(
                "SELECT cuenta FROM beworking.subscriptions WHERE contact_id = ? AND active = true ORDER BY id DESC LIMIT 1",
                String.class, contactId);
            if (cuenta != null && !cuenta.isBlank()) {
                return cuenta.toUpperCase();
            }
        } catch (EmptyResultDataAccessException ignored) {}
        return "PT";
    }

    private int resolveContactVatPercent(Long contactId, String cuenta) {
        String supplierCountry = "GT".equals(cuenta) ? "EE" : "ES";

        // JIT VIES validation: heal vat_valid before reading it.
        contactProfileService.ensureVatValidated(contactId);

        String taxId = null;
        String billingCountry = null;
        Boolean vatValid = null;
        try {
            var row = jdbcTemplate.queryForMap(
                "SELECT billing_tax_id, billing_country, vat_valid FROM beworking.contact_profiles WHERE id = ?",
                contactId);
            taxId = (String) row.get("billing_tax_id");
            billingCountry = (String) row.get("billing_country");
            vatValid = (Boolean) row.get("vat_valid");
        } catch (EmptyResultDataAccessException ignored) {}

        String customerCountry = resolveCustomerCountry(billingCountry, taxId);
        if (customerCountry == null) {
            return com.beworking.subscriptions.SubscriptionService.vatRateFor(supplierCountry);
        }
        if (Boolean.TRUE.equals(vatValid) && !supplierCountry.equals(customerCountry)) {
            LOGGER.info("Reverse charge: contact {} taxId={} (country={}) vs supplier {} → 0% VAT",
                contactId, taxId, customerCountry, supplierCountry);
            return 0;
        }
        int rate = com.beworking.subscriptions.SubscriptionService.vatRateFor(customerCountry);
        LOGGER.info("VAT resolved: contact {} taxId={} customerCountry={} supplier={} vatValid={} → {}%",
            contactId, taxId, customerCountry, supplierCountry, vatValid, rate);
        return rate;
    }

    private String resolveCustomerCountry(String billingCountry, String taxId) {
        return com.beworking.subscriptions.SubscriptionService.deriveCustomerCountry(taxId, billingCountry);
    }

    private void sendBookingEmails(PublicBookingRequest request, String status, String note) {
        try {
            String safePhone = request.getPhone() != null ? request.getPhone() : "—";
            String safeAttendees = request.getAttendees() != null ? String.valueOf(request.getAttendees()) : "—";
            String safeNote = note != null ? note : "—";

            String html = "<div style='font-family:Arial,Helvetica,sans-serif;max-width:600px;margin:0 auto;background:#ffffff;border-radius:12px;overflow:hidden;box-shadow:0 2px 12px rgba(0,0,0,0.08)'>"
                // ── Hero header with green gradient ──
                + "<div style='background:linear-gradient(135deg,#009624 0%,#00c853 100%);padding:40px 32px 32px;color:#ffffff'>"
                + "<p style='margin:0 0 4px;font-size:13px;letter-spacing:2px;text-transform:uppercase;opacity:0.85'>BEWORKING</p>"
                + "<h1 style='margin:0 0 8px;font-size:26px;font-weight:700;line-height:1.2'>Nueva Reserva</h1>"
                + "</div>"
                // ── Body ──
                + "<div style='padding:32px'>"
                + "<p style='margin:0 0 24px;font-size:16px;color:#333'>Se ha registrado una nueva reserva.</p>"
                // ── Details card ──
                + "<div style='background:#f5faf6;border-radius:10px;padding:20px 24px;border-left:4px solid #009624'>"
                + "<table style='border-collapse:collapse;width:100%'>"
                + "<tr><td style='padding:8px 12px 8px 0;color:#888;font-size:13px;white-space:nowrap'>Cliente</td>"
                + "<td style='padding:8px 0;font-size:15px;font-weight:700;color:#222'>" + request.getFirstName() + " " + request.getLastName() + "</td></tr>"
                + "<tr><td style='padding:8px 12px 8px 0;color:#888;font-size:13px;white-space:nowrap'>Email</td>"
                + "<td style='padding:8px 0;font-size:15px;color:#333'>" + request.getEmail() + "</td></tr>"
                + "<tr><td style='padding:8px 12px 8px 0;color:#888;font-size:13px;white-space:nowrap'>Tel\u00e9fono</td>"
                + "<td style='padding:8px 0;font-size:15px;color:#333'>" + safePhone + "</td></tr>"
                + "<tr><td style='padding:8px 12px 8px 0;color:#888;font-size:13px;white-space:nowrap'>Producto</td>"
                + "<td style='padding:8px 0;font-size:15px;font-weight:700;color:#222'>" + request.getProductName() + "</td></tr>"
                + "<tr><td style='padding:8px 12px 8px 0;color:#888;font-size:13px;white-space:nowrap'>Fecha</td>"
                + "<td style='padding:8px 0;font-size:15px;color:#333'>" + request.getDate() + "</td></tr>"
                + "<tr><td style='padding:8px 12px 8px 0;color:#888;font-size:13px;white-space:nowrap'>Horario</td>"
                + "<td style='padding:8px 0;font-size:15px;color:#333'>" + request.getStartTime() + " - " + request.getEndTime() + "</td></tr>"
                + "<tr><td style='padding:8px 12px 8px 0;color:#888;font-size:13px;white-space:nowrap'>Asistentes</td>"
                + "<td style='padding:8px 0;font-size:15px;color:#333'>" + safeAttendees + "</td></tr>"
                + "<tr><td style='padding:8px 12px 8px 0;color:#888;font-size:13px;white-space:nowrap'>Estado</td>"
                + "<td style='padding:8px 0;font-size:15px;color:#333'><span style='display:inline-block;padding:3px 10px;border-radius:12px;background:#e8f5e9;color:#2e7d32;font-size:13px;font-weight:600'>" + status + "</span></td></tr>"
                + "<tr><td style='padding:8px 12px 8px 0;color:#888;font-size:13px;white-space:nowrap'>Nota</td>"
                + "<td style='padding:8px 0;font-size:15px;color:#333'>" + safeNote + "</td></tr>"
                + "</table>"
                + "</div>"
                + "</div>"
                // ── Footer ──
                + "<div style='background:#f9f9f9;padding:16px 32px;text-align:center;border-top:1px solid #eee'>"
                + "<p style='margin:0;font-size:12px;color:#aaa'>\u00a9 BeWorking \u00b7 M\u00e1laga</p>"
                + "</div>"
                + "</div>";

            emailService.sendHtml("info@be-working.com", "Nueva Reserva - " + request.getProductName(), html);
        } catch (Exception e) {
            LOGGER.warn("Failed to send admin booking notification email", e);
        }

        // ── Client confirmation email ──
        if (request.getEmail() != null && !request.getEmail().isBlank()) {
            try {
                String safeName = (request.getFirstName() != null ? request.getFirstName() : "") + " " + (request.getLastName() != null ? request.getLastName() : "");
                safeName = safeName.trim().isEmpty() ? "Cliente" : safeName.trim();
                String safeProduct = request.getProductName() != null ? request.getProductName() : "—";
                String clientAttendees = request.getAttendees() != null ? String.valueOf(request.getAttendees()) : null;

                String clientHtml = "<div style='font-family:Arial,Helvetica,sans-serif;max-width:600px;margin:0 auto;background:#ffffff;border-radius:12px;overflow:hidden;box-shadow:0 2px 12px rgba(0,0,0,0.08)'>"
                    // ── Hero header ──
                    + "<div style='background:linear-gradient(135deg,#009624 0%,#00c853 100%);padding:40px 32px 32px;color:#ffffff'>"
                    + "<p style='margin:0 0 4px;font-size:13px;letter-spacing:2px;text-transform:uppercase;opacity:0.85'>BEWORKING</p>"
                    + "<h1 style='margin:0 0 8px;font-size:26px;font-weight:700;line-height:1.2'>Confirmaci\u00f3n de Reserva</h1>"
                    + "</div>"
                    // ── Body ──
                    + "<div style='padding:32px'>"
                    + "<p style='margin:0 0 8px;font-size:16px;color:#333'>Hola <strong>" + safeName + "</strong>, tu reserva ha sido confirmada.</p>"
                    + "<p style='margin:0 0 24px;font-size:14px;color:#666'>A continuaci\u00f3n los detalles de tu reserva:</p>"
                    // ── Details card ──
                    + "<div style='background:#f5faf6;border-radius:10px;padding:20px 24px;border-left:4px solid #009624'>"
                    + "<table style='border-collapse:collapse;width:100%'>"
                    + "<tr><td style='padding:8px 12px 8px 0;color:#888;font-size:13px;white-space:nowrap'>Sala</td>"
                    + "<td style='padding:8px 0;font-size:15px;font-weight:700;color:#222'>" + safeProduct + "</td></tr>"
                    + "<tr><td style='padding:8px 12px 8px 0;color:#888;font-size:13px;white-space:nowrap'>Fecha</td>"
                    + "<td style='padding:8px 0;font-size:15px;color:#333'>" + request.getDate() + "</td></tr>"
                    + "<tr><td style='padding:8px 12px 8px 0;color:#888;font-size:13px;white-space:nowrap'>Horario</td>"
                    + "<td style='padding:8px 0;font-size:15px;color:#333'>" + request.getStartTime() + " - " + request.getEndTime() + "</td></tr>"
                    + (clientAttendees != null ? "<tr><td style='padding:8px 12px 8px 0;color:#888;font-size:13px;white-space:nowrap'>Asistentes</td><td style='padding:8px 0;font-size:15px;color:#333'>" + clientAttendees + "</td></tr>" : "")
                    + "</table>"
                    + "</div>"
                    // ── Info boxes ──
                    + "<table style='border-collapse:collapse;width:100%;margin-top:28px'><tr>"
                    + "<td style='width:50%;padding:0 8px 0 0;vertical-align:top'>"
                    + "<div style='background:#f5faf6;border-radius:8px;padding:16px'>"
                    + "<p style='margin:0 0 4px;font-size:14px;font-weight:700;color:#333'>Cambios o cancelaciones</p>"
                    + "<p style='margin:0;font-size:12px;color:#888'>Cont\u00e1ctanos con antelaci\u00f3n para gestionar cualquier cambio.</p>"
                    + "</div></td>"
                    + "<td style='width:50%;padding:0 0 0 8px;vertical-align:top'>"
                    + "<div style='background:#f5faf6;border-radius:8px;padding:16px'>"
                    + "<p style='margin:0 0 4px;font-size:14px;font-weight:700;color:#333'>Acceso al centro</p>"
                    + "<p style='margin:0;font-size:12px;color:#888'>Presenta este email en recepci\u00f3n a tu llegada.</p>"
                    + "</div></td>"
                    + "</tr></table>"
                    // ── Invoice access box ──
                    + "<div style='background:#f5faf6;border-radius:8px;padding:16px;margin-top:12px'>"
                    + "<p style='margin:0 0 4px;font-size:14px;font-weight:700;color:#333'>Facturaci\u00f3n</p>"
                    + "<p style='margin:0;font-size:12px;color:#888'>Accede a tus facturas desde tu panel en "
                    + "<a href='https://app.be-working.com' style='color:#009624;text-decoration:none;font-weight:600'>app.be-working.com</a></p>"
                    + "</div>"
                    // ── Contact line ──
                    + "<p style='margin:28px 0 0;font-size:13px;color:#888;text-align:center'>"
                    + "\u00bfNecesitas ayuda? Responde a este correo o escr\u00edbenos por WhatsApp: "
                    + "<a href='https://wa.me/34640369759' style='color:#009624;text-decoration:none;font-weight:600'>+34 640 369 759</a></p>"
                    + "</div>"
                    // ── Footer ──
                    + "<div style='background:#f9f9f9;padding:16px 32px;text-align:center;border-top:1px solid #eee'>"
                    + "<p style='margin:0;font-size:12px;color:#aaa'>\u00a9 BeWorking \u00b7 M\u00e1laga</p>"
                    + "</div>"
                    + "</div>";

                emailService.sendHtml(request.getEmail(), "Confirmaci\u00f3n de reserva - BeWorking", clientHtml);
            } catch (Exception e) {
                LOGGER.warn("Failed to send client booking confirmation email", e);
            }
        }
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
        // Pessimistic lock on the product row — serializes concurrent bookings for the same product
        Producto producto = productoRepository.findByIdForUpdate(request.getProductoId())
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

        Reserva savedReserva = reservaRepository.save(reserva);

        bloqueosToPersist.forEach(bloqueo -> {
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
