package com.beworking.subscriptions;

import com.beworking.contacts.ViesVatService;
import com.beworking.cuentas.Cuenta;
import com.beworking.cuentas.CuentaService;
import com.beworking.invoices.InvoiceCategory;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SubscriptionService {

    private static final Logger logger = LoggerFactory.getLogger(SubscriptionService.class);

    private final SubscriptionRepository subscriptionRepository;
    private final CuentaService cuentaService;
    private final JdbcTemplate jdbcTemplate;
    private final ViesVatService viesVatService;
    private final com.beworking.contacts.ContactProfileService contactProfileService;
    private final StripeTaxSyncClient stripeTaxSyncClient;
    private final com.beworking.tax.TaxResolver taxResolver;
    private final com.beworking.invoices.BillingSnapshotService billingSnapshotService;

    public SubscriptionService(SubscriptionRepository subscriptionRepository,
                               CuentaService cuentaService,
                               JdbcTemplate jdbcTemplate,
                               ViesVatService viesVatService,
                               @org.springframework.context.annotation.Lazy com.beworking.contacts.ContactProfileService contactProfileService,
                               StripeTaxSyncClient stripeTaxSyncClient,
                               com.beworking.tax.TaxResolver taxResolver,
                               com.beworking.invoices.BillingSnapshotService billingSnapshotService) {
        this.subscriptionRepository = subscriptionRepository;
        this.cuentaService = cuentaService;
        this.jdbcTemplate = jdbcTemplate;
        this.viesVatService = viesVatService;
        this.contactProfileService = contactProfileService;
        this.stripeTaxSyncClient = stripeTaxSyncClient;
        this.taxResolver = taxResolver;
        this.billingSnapshotService = billingSnapshotService;
    }

    public List<Subscription> findAll() {
        return subscriptionRepository.findAll();
    }

    public int updateContactStatusIfTrial(Long contactId, String newStatus) {
        return jdbcTemplate.update(
            "UPDATE beworking.contact_profiles SET status = ?, status_changed_at = NOW() WHERE id = ? AND status = 'Trial'",
            newStatus, contactId);
    }

    /**
     * Unconditional status flip used by the subscription-cancelled webhook.
     * Caller is expected to have already checked there are no other active
     * subs on this contact. Stamps status_changed_at for audit.
     */
    public int updateContactStatus(Long contactId, String newStatus) {
        return jdbcTemplate.update(
            "UPDATE beworking.contact_profiles SET status = ?, status_changed_at = NOW() WHERE id = ?",
            newStatus, contactId);
    }

    public List<Subscription> findByContactId(Long contactId) {
        return subscriptionRepository.findByContactId(contactId);
    }

    public List<Subscription> findByContactIdAndActiveTrue(Long contactId) {
        return subscriptionRepository.findByContactIdAndActiveTrue(contactId);
    }

    public Optional<Subscription> findById(Integer id) {
        return subscriptionRepository.findById(id);
    }

    public List<Subscription> findActiveDeskSubscriptions() {
        // Desks currently occupied = subscriptions whose paid coverage contains
        // today. A sub cancelled mid-period keeps its desk until its paid-through
        // end_date, so the admin floor plan doesn't free a still-paid desk (which
        // would let it be double-booked).
        return subscriptionRepository.findActiveCoveringDate(java.time.LocalDate.now());
    }

    public Optional<Subscription> findByStripeSubscriptionId(String stripeSubscriptionId) {
        return subscriptionRepository.findByStripeSubscriptionId(stripeSubscriptionId);
    }

    public Optional<Subscription> findByStripeCustomerId(String stripeCustomerId) {
        return subscriptionRepository.findFirstByStripeCustomerIdAndActiveTrue(stripeCustomerId);
    }

    public Subscription save(Subscription subscription) {
        return subscriptionRepository.save(subscription);
    }

    public void deactivate(Integer id, LocalDate paidThrough) {
        subscriptionRepository.findById(id).ifPresent(sub -> {
            sub.setActive(false);
            // Hold the desk until the paid-through date (Stripe current_period_end)
            // instead of freeing it on the cancellation date — otherwise a desk
            // that's cancelled but still paid shows free and can be double-booked.
            // Fall back to the monthly anniversary of the start date when the
            // paid-through is unknown (e.g. bank-transfer subs with no Stripe
            // period): a sub that started on the 10th is paid through the 10th of
            // the following month, NOT the calendar month-end.
            LocalDate end = paidThrough != null
                ? paidThrough
                : monthlyPaidThrough(sub.getStartDate(), LocalDate.now());
            sub.setEndDate(end);
            subscriptionRepository.save(sub);
        });
    }

    /**
     * The end of the current monthly billing period for a sub anchored on
     * {@code start}, as of {@code ref}: the first monthly anniversary of the
     * start date strictly after {@code ref}. E.g. start=May 10, ref=Jun 11 → Jul 10.
     */
    static LocalDate monthlyPaidThrough(LocalDate start, LocalDate ref) {
        if (start == null) {
            return ref.withDayOfMonth(1).plusMonths(1).minusDays(1);
        }
        long months = java.time.temporal.ChronoUnit.MONTHS.between(start, ref);
        LocalDate end = start.plusMonths(months + 1);
        while (!end.isAfter(ref)) {
            end = end.plusMonths(1);
        }
        return end;
    }

    /**
     * Creates a local invoice (facturas + facturasdesglose) from a subscription
     * when a Stripe subscription invoice is paid or fails.
     * Returns null if a duplicate invoice already exists.
     */
    @Transactional
    public Map<String, Object> createInvoiceFromSubscription(
            Subscription subscription, SubscriptionInvoicePayload payload) {

        // Deduplication: check if invoice with this Stripe invoice ID already exists
        if (payload.getStripeInvoiceId() != null && !payload.getStripeInvoiceId().isBlank()) {
            try {
                Integer existing = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM beworking.facturas WHERE stripeinvoiceid = ?",
                    Integer.class, payload.getStripeInvoiceId());
                if (existing != null && existing > 0) {
                    // If it already exists and this is a successful payment, update to Pagado
                    if ("paid".equalsIgnoreCase(payload.getStatus())) {
                        String pdfUrl = payload.getInvoicePdf();
                        if (pdfUrl != null && !pdfUrl.isBlank()) {
                            jdbcTemplate.update(
                                "UPDATE beworking.facturas SET estado = 'Pagado', stripepaymentintentid1 = ?, stripepaymentintentstatus1 = 'succeeded', holdedinvoicepdf = ? WHERE stripeinvoiceid = ? AND estado <> 'Pagado'",
                                payload.getStripePaymentIntentId(), pdfUrl, payload.getStripeInvoiceId());
                        } else {
                            jdbcTemplate.update(
                                "UPDATE beworking.facturas SET estado = 'Pagado', stripepaymentintentid1 = ?, stripepaymentintentstatus1 = 'succeeded' WHERE stripeinvoiceid = ? AND estado <> 'Pagado'",
                                payload.getStripePaymentIntentId(), payload.getStripeInvoiceId());
                        }
                        // Sync linked bloqueos to Pagado
                        jdbcTemplate.update("""
                            UPDATE beworking.bloqueos b SET estado = 'Pagado'
                            FROM beworking.facturasdesglose fd
                            JOIN beworking.facturas f ON f.idfactura = fd.idfacturadesglose
                            WHERE fd.idbloqueovinculado = b.id AND f.stripeinvoiceid = ? AND b.estado <> 'Pagado'
                            """, payload.getStripeInvoiceId());
                        // Reactivate contact (aging cron may have demoted before payment landed)
                        jdbcTemplate.update("""
                            UPDATE beworking.contact_profiles
                               SET status = 'Activo', status_changed_at = NOW()
                             WHERE id = ?
                               AND status <> 'Activo'
                            """, subscription.getContactId());
                    }
                    logger.info("Invoice already exists for stripeInvoiceId={}, skipping creation",
                        payload.getStripeInvoiceId());
                    return null;
                }
            } catch (EmptyResultDataAccessException ignored) {
            }
        }

        // Active-sub gate: block CREATION of new facturas for inactive subs.
        // Comes AFTER the dedup branch above so a late payment on an already
        // existing factura can still flip it to Pagado even if the sub was
        // cancelled in the meantime (Claudia GT5733 case, 2026-05-25).
        if (!Boolean.TRUE.equals(subscription.getActive())) {
            logger.warn("Subscription {} is inactive; skipping factura creation for stripeInvoiceId={}",
                subscription.getStripeSubscriptionId(), payload.getStripeInvoiceId());
            return null;
        }

        // Resolve cuenta
        String cuentaCodigo = subscription.getCuenta();
        Integer cuentaId = null;
        Optional<Cuenta> cuentaOpt = cuentaService.getCuentaByCodigo(cuentaCodigo);
        if (cuentaOpt.isPresent()) {
            cuentaId = cuentaOpt.get().getId();
        }

        // Generate invoice number — reuse the one already reserved during
        // invoice.created ONLY when it's one of OUR numbers (PT/GT/OF + digits).
        // A charge_automatically sub created directly (booking-app desk sub) has
        // no pre-reservation step, so Stripe's own auto-number (e.g.
        // "43F33606-0035") leaks in via stripeInvoiceNumber. Reusing that yields a
        // malformed idfactura and an int-overflow NumberFormatException (#282).
        // When the reserved number isn't ours, generate a proper sequential one.
        // Prefer the number persisted at reservation time, keyed by stripeInvoiceId
        // (V96). This is authoritative and immune to the invoice.created→finalized
        // race that previously let a second number be minted (Stripe PT4958 /
        // BeWorking PT4959, leaving PT4958 a gap). Fall back to Stripe's echoed
        // number, then to a fresh sequential one.
        var persistedReservation = findReservedInvoiceNumber(payload.getStripeInvoiceId());
        String reserved = payload.getStripeInvoiceNumber();
        boolean reservedIsOurs = reserved != null && reserved.matches("(?i)^(PT|GT|OF)\\d+$");
        String invoiceNumber;
        if (persistedReservation.isPresent()) {
            invoiceNumber = persistedReservation.get();
            logger.info("Reusing persisted reserved invoice number {} for stripeInvoiceId={}",
                invoiceNumber, payload.getStripeInvoiceId());
        } else if (reservedIsOurs) {
            invoiceNumber = reserved;
            logger.info("Reusing pre-reserved invoice number {} for stripeInvoiceId={}",
                invoiceNumber, payload.getStripeInvoiceId());
        } else if (cuentaId != null) {
            invoiceNumber = cuentaService.generateNextInvoiceNumber(cuentaId);
        } else {
            invoiceNumber = cuentaService.generateNextInvoiceNumber("PT");
        }

        // Parse invoice number to numeric idfactura
        String numericPart = invoiceNumber.replaceAll("[^0-9]", "");
        int invoiceId = numericPart.isEmpty()
            ? jdbcTemplate.queryForObject("SELECT COALESCE(MAX(idfactura), 0) + 1 FROM beworking.facturas", Integer.class)
            : Integer.parseInt(numericPart);

        // Generate internal primary key
        Long nextInternalId = jdbcTemplate.queryForObject(
            "SELECT nextval('beworking.facturas_id_seq')", Long.class);

        // Use Stripe subtotal when available (handles prorated first invoices correctly).
        // Fall back to subscription monthly amount for bank transfer or missing data.
        BigDecimal subtotal;
        if (payload.getSubtotalCents() != null && payload.getSubtotalCents() > 0) {
            subtotal = BigDecimal.valueOf(payload.getSubtotalCents())
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        } else {
            subtotal = subscription.getMonthlyAmount();
        }
        int vatPercent = resolveVatPercent(subscription);
        BigDecimal vatAmount = subtotal.multiply(BigDecimal.valueOf(vatPercent))
            .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        BigDecimal total = subtotal.add(vatAmount);

        String description = subscription.getDescription();

        // Determine status
        String estado = "paid".equalsIgnoreCase(payload.getStatus()) ? "Pagado" : "Pendiente";

        // Invoice date = period start or today
        LocalDate invoiceDate = payload.getPeriodStart() != null && !payload.getPeriodStart().isBlank()
            ? LocalDate.parse(payload.getPeriodStart())
            : LocalDate.now();

        // Insert facturas record
        String invoicePdf = payload.getInvoicePdf();
        jdbcTemplate.update(
            """
            INSERT INTO beworking.facturas (
                id, idfactura, idcliente, holdedcuenta, id_cuenta,
                fechacreacionreal, estado, descripcion,
                total, iva, totaliva, creacionfecha,
                stripeinvoiceid, stripepaymentintentid1, stripepaymentintentstatus1,
                holdedinvoicenum, holdedinvoicepdf, category
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, ?, ?, ?, ?, ?, ?)
            """,
            nextInternalId,
            invoiceId,
            subscription.getContactId(),
            cuentaCodigo,
            cuentaId,
            invoiceDate,
            estado,
            description,
            total,
            vatPercent,
            vatAmount,
            payload.getStripeInvoiceId(),
            payload.getStripePaymentIntentId(),
            "paid".equalsIgnoreCase(payload.getStatus()) ? "succeeded" : null,
            invoiceNumber,
            invoicePdf != null && !invoicePdf.isBlank() ? invoicePdf : null,
            resolveSubscriptionCategory(subscription)
        );
        billingSnapshotService.snapshot(nextInternalId, subscription.getContactId());

        // Insert line item
        Long nextDesgloseId = jdbcTemplate.queryForObject(
            "SELECT nextval('beworking.facturasdesglose_id_seq')", Long.class);

        jdbcTemplate.update(
            """
            INSERT INTO beworking.facturasdesglose (
                id, idfacturadesglose, conceptodesglose, precioundesglose,
                cantidaddesglose, totaldesglose, desgloseconfirmado, idbloqueovinculado, factura_id
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            nextDesgloseId,
            invoiceId,
            description,
            subtotal,
            BigDecimal.ONE,
            subtotal,
            1,
            null,
            nextInternalId
        );

        // Reactivate contact on paid invoice (aging cron may have demoted before payment)
        if ("paid".equalsIgnoreCase(payload.getStatus())) {
            jdbcTemplate.update("""
                UPDATE beworking.contact_profiles
                   SET status = 'Activo', status_changed_at = NOW()
                 WHERE id = ?
                   AND status <> 'Activo'
                """, subscription.getContactId());
        }

        logger.info("Created subscription invoice: invoiceNumber={} contactId={} estado={} stripeInvoiceId={}",
            invoiceNumber, subscription.getContactId(), estado, payload.getStripeInvoiceId());

        Map<String, Object> response = new HashMap<>();
        response.put("id", nextInternalId);
        response.put("idFactura", invoiceId);
        response.put("invoiceNumber", invoiceNumber);
        response.put("status", estado);
        response.put("total", total);
        return response;
    }

    /**
     * Creates a Pendiente invoice for a bank_transfer subscription for the given month.
     * month format: "yyyy-MM" (e.g. "2026-03")
     */
    @Transactional
    /** Number of months billed per cycle for a billing interval (default monthly = 1). */
    public static int monthsForInterval(String interval) {
        if (interval == null) return 1;
        return switch (interval.toLowerCase()) {
            case "year" -> 12;
            case "half_year" -> 6;
            case "quarter" -> 3;
            default -> 1;
        };
    }

    public Map<String, Object> createBankTransferInvoice(Subscription subscription, String month) {
        // Resolve cuenta
        String cuentaCodigo = subscription.getCuenta();
        Integer cuentaId = null;
        Optional<Cuenta> cuentaOpt = cuentaService.getCuentaByCodigo(cuentaCodigo);
        if (cuentaOpt.isPresent()) {
            cuentaId = cuentaOpt.get().getId();
        }

        // Generate invoice number
        String invoiceNumber;
        if (cuentaId != null) {
            invoiceNumber = cuentaService.generateNextInvoiceNumber(cuentaId);
        } else {
            invoiceNumber = cuentaService.generateNextInvoiceNumber("PT");
        }

        // Parse invoice number to numeric idfactura
        String numericPart = invoiceNumber.replaceAll("[^0-9]", "");
        int invoiceId = numericPart.isEmpty()
            ? jdbcTemplate.queryForObject("SELECT COALESCE(MAX(idfactura), 0) + 1 FROM beworking.facturas", Integer.class)
            : Integer.parseInt(numericPart);

        // Generate internal primary key
        Long nextInternalId = jdbcTemplate.queryForObject(
            "SELECT nextval('beworking.facturas_id_seq')", Long.class);

        // VAT calculation. The stored amount is the MONTHLY rate; a non-monthly
        // billing interval charges that × the number of months in the cycle
        // (quarter ×3, half-year ×6, year ×12).
        BigDecimal subtotal = subscription.getMonthlyAmount()
            .multiply(BigDecimal.valueOf(monthsForInterval(subscription.getBillingInterval())));
        int vatPercent = resolveVatPercent(subscription);
        BigDecimal vatAmount = subtotal.multiply(BigDecimal.valueOf(vatPercent))
            .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        BigDecimal total = subtotal.add(vatAmount);

        String description = subscription.getDescription();
        LocalDate monthDate = LocalDate.parse(month + "-01");

        // Invoice date = 1st of the month
        LocalDate invoiceDate = monthDate;

        // Insert facturas record (no Stripe fields)
        jdbcTemplate.update(
            """
            INSERT INTO beworking.facturas (
                id, idfactura, idcliente, holdedcuenta, id_cuenta,
                fechacreacionreal, estado, descripcion,
                total, iva, totaliva, creacionfecha,
                holdedinvoicenum, category
            ) VALUES (?, ?, ?, ?, ?, ?, 'Pendiente', ?, ?, ?, ?, CURRENT_TIMESTAMP, ?, ?)
            """,
            nextInternalId,
            invoiceId,
            subscription.getContactId(),
            cuentaCodigo,
            cuentaId,
            invoiceDate,
            description,
            total,
            vatPercent,
            vatAmount,
            invoiceNumber,
            resolveSubscriptionCategory(subscription)
        );
        billingSnapshotService.snapshot(nextInternalId, subscription.getContactId());

        // Insert line item
        Long nextDesgloseId = jdbcTemplate.queryForObject(
            "SELECT nextval('beworking.facturasdesglose_id_seq')", Long.class);

        jdbcTemplate.update(
            """
            INSERT INTO beworking.facturasdesglose (
                id, idfacturadesglose, conceptodesglose, precioundesglose,
                cantidaddesglose, totaldesglose, desgloseconfirmado, idbloqueovinculado, factura_id
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            nextDesgloseId,
            invoiceId,
            description,
            subtotal,
            BigDecimal.ONE,
            subtotal,
            1,
            null,
            nextInternalId
        );

        logger.info("Created bank_transfer invoice: invoiceNumber={} contactId={} month={}",
            invoiceNumber, subscription.getContactId(), month);

        Map<String, Object> response = new HashMap<>();
        response.put("id", nextInternalId);
        response.put("idFactura", invoiceId);
        response.put("invoiceNumber", invoiceNumber);
        response.put("status", "Pendiente");
        response.put("total", total);
        return response;
    }

    /**
     * Returns active bank_transfer subscriptions that haven't been invoiced for the given month.
     */
    public List<Subscription> findBankTransferDueForMonth(String month) {
        return subscriptionRepository.findBankTransferDueForMonth(month);
    }

    // ── VAT resolution ──────────────────────────────────────────────────

    private static final java.util.Set<String> EU_VAT_PREFIXES = java.util.Set.of(
        "AT","BE","BG","CY","CZ","DE","DK","EE","EL","ES","FI","FR",
        "HR","HU","IE","IT","LT","LU","LV","MT","NL","PL","PT","RO",
        "SE","SI","SK"
    );

    private static final java.util.Map<String, Integer> EU_VAT_RATES = java.util.Map.ofEntries(
        java.util.Map.entry("AT", 20), java.util.Map.entry("BE", 21),
        java.util.Map.entry("BG", 20), java.util.Map.entry("CY", 19),
        java.util.Map.entry("CZ", 21), java.util.Map.entry("DE", 19),
        java.util.Map.entry("DK", 25), java.util.Map.entry("EE", 24),
        java.util.Map.entry("ES", 21), java.util.Map.entry("FI", 25),
        java.util.Map.entry("FR", 20), java.util.Map.entry("EL", 24),
        java.util.Map.entry("HR", 25), java.util.Map.entry("HU", 27),
        java.util.Map.entry("IE", 23), java.util.Map.entry("IT", 22),
        java.util.Map.entry("LT", 21), java.util.Map.entry("LU", 17),
        java.util.Map.entry("LV", 21), java.util.Map.entry("MT", 18),
        java.util.Map.entry("NL", 21), java.util.Map.entry("PL", 23),
        java.util.Map.entry("PT", 23), java.util.Map.entry("RO", 19),
        java.util.Map.entry("SE", 25), java.util.Map.entry("SI", 22),
        java.util.Map.entry("SK", 23)
    );

    /**
     * Returns the VAT percentage to apply for invoices on this subscription.
     *
     * Lock-in (V48 behaviour, since 2026-05): when the subscription has a
     * vat_percent stored, that value is the source of truth and is returned
     * as-is. The rate was computed at sub creation time or set explicitly via
     * an admin re-validation. Re-resolving every cycle was the cause of the
     * €15 ↔ €18.15 oscillation customers were complaining about, because
     * transient VIES failures kept flipping vat_valid and dragging the rate
     * with it.
     *
     * Legacy fallback: if the sub has no stored vat_percent (very old rows
     * not covered by V48), recompute from contact billing data + cuenta.
     */
    /**
     * Reserve the next invoice number for a subscription's upcoming Stripe invoice
     * and persist it keyed by stripeInvoiceId (V96), so {@link #createInvoiceFromSubscription}
     * can later reuse the EXACT number deterministically instead of depending on
     * Stripe echoing it back via invoice.number (which races with finalize and
     * produced a divergent number + a gap, e.g. PT4958 vs PT4959).
     *
     * <p>Idempotent on stripeInvoiceId: a re-fired invoice.created returns the
     * same number without bumping the counter again. With no stripeInvoiceId it
     * behaves as before (generate, return, persist nothing).
     */
    public String reserveInvoiceNumber(Subscription subscription, String stripeInvoiceId) {
        String cuentaCodigo = subscription.getCuenta();
        boolean keyed = stripeInvoiceId != null && !stripeInvoiceId.isBlank();
        if (keyed) {
            var existing = findReservedInvoiceNumber(stripeInvoiceId);
            if (existing.isPresent()) {
                return existing.get();
            }
        }
        String invoiceNumber;
        Optional<Cuenta> cuentaOpt = cuentaService.getCuentaByCodigo(cuentaCodigo);
        if (cuentaOpt.isPresent()) {
            invoiceNumber = cuentaService.generateNextInvoiceNumber(cuentaOpt.get().getId());
        } else {
            invoiceNumber = cuentaService.generateNextInvoiceNumber("PT");
        }
        if (keyed) {
            jdbcTemplate.update(
                "INSERT INTO beworking.invoice_number_reservations (stripe_invoice_id, invoice_number, cuenta) "
                    + "VALUES (?, ?, ?) ON CONFLICT (stripe_invoice_id) DO NOTHING",
                stripeInvoiceId, invoiceNumber, cuentaCodigo);
            // If a concurrent reservation for the same invoice won the insert,
            // return the persisted one so both callers agree.
            return findReservedInvoiceNumber(stripeInvoiceId).orElse(invoiceNumber);
        }
        return invoiceNumber;
    }

    /** Looks up a persisted invoice-number reservation by Stripe invoice id (V96). */
    public Optional<String> findReservedInvoiceNumber(String stripeInvoiceId) {
        if (stripeInvoiceId == null || stripeInvoiceId.isBlank()) {
            return Optional.empty();
        }
        var rows = jdbcTemplate.query(
            "SELECT invoice_number FROM beworking.invoice_number_reservations WHERE stripe_invoice_id = ?",
            (rs, n) -> rs.getString("invoice_number"), stripeInvoiceId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    int resolveVatPercent(Subscription subscription) {
        // Lock-in path: stored value wins.
        if (subscription.getVatPercent() != null) {
            return subscription.getVatPercent();
        }
        // Legacy path: delegate to TaxResolver. Triggers JIT VIES via the
        // legacy fresh-compute branch.
        contactProfileService.ensureVatValidated(subscription.getContactId());
        return taxResolver.computeFreshForContact(subscription.getContactId(), subscription.getCuenta());
    }

    /**
     * Resolves the invoice category for a subscription invoice — from the
     * linked product's type when known, otherwise a description heuristic.
     * Subscriptions are virtual offices by default.
     */
    public String resolveSubscriptionCategory(Subscription subscription) {
        if (subscription.getProductoId() != null) {
            try {
                String tipo = jdbcTemplate.queryForObject(
                    "SELECT tipo FROM beworking.productos WHERE id = ?",
                    String.class, subscription.getProductoId());
                if (tipo != null) {
                    return InvoiceCategory.fromProductTipo(tipo);
                }
            } catch (EmptyResultDataAccessException ignored) {
                // No matching product — fall through to the description heuristic.
            }
        }
        String desc = subscription.getDescription() == null
            ? "" : subscription.getDescription().toLowerCase();
        if (desc.contains("coworking") || desc.contains("mesa") || desc.contains("desk")) {
            return InvoiceCategory.COWORKING;
        }
        return InvoiceCategory.VIRTUAL_OFFICE;
    }

    /**
     * Always computes the VAT rate from scratch, ignoring any stored
     * vat_percent. Delegates to {@link com.beworking.tax.TaxResolver}.
     * Used by the admin "Re-validate VAT" endpoint via {@link #relockVatPercent}.
     */
    private int computeFreshVatPercent(Subscription subscription) {
        contactProfileService.ensureVatValidated(subscription.getContactId());
        return taxResolver.computeFreshForContact(subscription.getContactId(), subscription.getCuenta());
    }

    /**
     * Force-recompute and persist vat_percent on a subscription. Used by the
     * admin "Re-validate VAT" trigger when a customer's VIES status has
     * genuinely changed (e.g. they just registered, or are disputing a 21%
     * lock-in by claiming intra-community status).
     *
     * Returns a {@link RelockResult} so the caller can show before/after in
     * the admin UI.
     */
    @Transactional
    public RelockResult relockVatPercent(Subscription subscription) {
        Integer previous = subscription.getVatPercent();
        int fresh = computeFreshVatPercent(subscription);
        boolean changed = previous == null || previous != fresh;
        if (changed) {
            subscription.setVatPercent(fresh);
            subscription.setUpdatedAt(java.time.LocalDateTime.now());
            subscriptionRepository.save(subscription);
            logger.info("Relocked VAT for sub {}: {} → {}%", subscription.getId(), previous, fresh);
        }
        // Always sync Stripe — even when the local rate didn't change, Stripe's
        // config might still be drifted from a prior buggy run. Best-effort.
        if (subscription.getStripeSubscriptionId() != null
                && !subscription.getStripeSubscriptionId().isBlank()) {
            stripeTaxSyncClient.syncSubscriptionTax(
                subscription.getStripeSubscriptionId(), fresh, subscription.getCuenta());
        }
        return new RelockResult(subscription.getId(), previous, fresh, changed);
    }

    /**
     * Bulk-sync Stripe tax config for every active subscription with a
     * stripe_subscription_id. Idempotent. Used as a one-shot reconciliation
     * after V48 lands the canonical vat_percent on the local DB — Stripe needs
     * to be told about the locked rates so its monthly invoices match ours.
     *
     * Returns counts: {processed, synced, skipped}.
     */
    /**
     * Bulk re-lock: for every active sub, recompute vat_percent from fresh
     * contact data + push Stripe tax sync. Used on prod AFTER the post-deploy
     * reseed completes, to apply newly-healed vat_valid values to subs that
     * V48/V49 (which run on Flyway startup) couldn't see yet.
     *
     * Returns counts: {processed, changed, unchanged, errors}.
     */
    public java.util.Map<String, Integer> bulkRelockAllActiveSubs() {
        int processed = 0, changed = 0, unchanged = 0, errors = 0;
        for (Subscription sub : subscriptionRepository.findByActiveTrue()) {
            processed++;
            try {
                RelockResult r = relockVatPercent(sub);
                if (r.changed()) changed++; else unchanged++;
            } catch (Exception e) {
                errors++;
                logger.warn("bulkRelockAllActiveSubs: sub {} failed: {}", sub.getId(), e.getMessage());
            }
            // Throttle for VIES (legacy fresh path) + Stripe rate limits.
            try { Thread.sleep(300); } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return java.util.Map.of(
            "processed", processed,
            "changed", changed,
            "unchanged", unchanged,
            "errors", errors);
    }

    public java.util.Map<String, Integer> bulkSyncStripeTax() {
        int processed = 0, synced = 0, skipped = 0, errors = 0;
        for (Subscription sub : subscriptionRepository.findByActiveTrue()) {
            processed++;
            if (sub.getStripeSubscriptionId() == null || sub.getStripeSubscriptionId().isBlank()) {
                skipped++;
                continue;
            }
            if (sub.getVatPercent() == null) {
                skipped++;
                continue;
            }
            boolean ok = stripeTaxSyncClient.syncSubscriptionTax(
                sub.getStripeSubscriptionId(), sub.getVatPercent(), sub.getCuenta());
            if (ok) synced++; else errors++;
            // Stripe rate-limit safety: small delay between calls.
            try { Thread.sleep(200); } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return java.util.Map.of(
            "processed", processed,
            "synced", synced,
            "skipped", skipped,
            "errors", errors);
    }

    public record RelockResult(Integer subId, Integer previousVatPercent, int newVatPercent, boolean changed) {}

    /**
     * Resolves the customer country for VAT purposes:
     *   1. EU country prefix in the tax ID (e.g., "ESB09665258" → ES).
     *   2. billing_country mapped via countryNameToIso.
     *   3. ES default (BeWorking is Spain-based).
     *
     * Returns null only if the resolved country is not in the EU set —
     * caller should treat that as "fallback to supplier-country VAT".
     */
    public static String deriveCustomerCountry(String taxId, String billingCountry) {
        if (taxId != null && !taxId.isBlank()) {
            String normalized = taxId.trim().replaceAll("\\s+", "").toUpperCase();
            if (normalized.length() >= 2 && EU_VAT_PREFIXES.contains(normalized.substring(0, 2))) {
                return normalized.substring(0, 2);
            }
        }
        String iso = countryNameToIso(billingCountry);
        if (iso == null) iso = "ES";
        return EU_VAT_PREFIXES.contains(iso) ? iso : null;
    }

    /** Returns the standard VAT rate for an ISO-2 country, or 21 as a safe default. */
    public static int vatRateFor(String iso) {
        if (iso == null) return 21;
        return EU_VAT_RATES.getOrDefault(iso.toUpperCase(), 21);
    }

    /** True when the given ISO-2 code is an EU member (matches EU_VAT_PREFIXES). */
    public static boolean isEuCountry(String iso) {
        return iso != null && EU_VAT_PREFIXES.contains(iso.toUpperCase());
    }

    public static String countryNameToIso(String countryName) {
        if (countryName == null || countryName.isBlank()) return null;
        String s = countryName.trim();
        if (s.length() == 2) return s.toUpperCase();
        return switch (s.toLowerCase()) {
            case "spain", "españa" -> "ES";
            case "ireland" -> "IE";
            case "italy", "italia" -> "IT";
            case "france", "francia" -> "FR";
            case "germany", "alemania" -> "DE";
            case "portugal" -> "PT";
            case "netherlands", "holanda", "países bajos" -> "NL";
            case "belgium", "bélgica", "belgica" -> "BE";
            case "sweden", "suecia" -> "SE";
            case "denmark", "dinamarca" -> "DK";
            case "finland", "finlandia" -> "FI";
            case "austria" -> "AT";
            case "greece", "grecia" -> "EL";
            case "poland", "polonia" -> "PL";
            case "czech republic", "república checa", "czechia" -> "CZ";
            case "hungary", "hungría", "hungria" -> "HU";
            case "romania", "rumania" -> "RO";
            case "bulgaria" -> "BG";
            case "croatia", "croacia" -> "HR";
            case "slovakia", "eslovaquia" -> "SK";
            case "slovenia", "eslovenia" -> "SI";
            case "estonia" -> "EE";
            case "latvia", "letonia" -> "LV";
            case "lithuania", "lituania" -> "LT";
            case "luxembourg", "luxemburgo" -> "LU";
            case "malta" -> "MT";
            case "cyprus", "chipre" -> "CY";
            default -> null;
        };
    }

}
