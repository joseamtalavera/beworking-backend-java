package com.beworking.subscriptions;

import com.beworking.contacts.ViesVatService;
import com.beworking.cuentas.Cuenta;
import com.beworking.cuentas.CuentaService;
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

    public SubscriptionService(SubscriptionRepository subscriptionRepository,
                               CuentaService cuentaService,
                               JdbcTemplate jdbcTemplate,
                               ViesVatService viesVatService,
                               @org.springframework.context.annotation.Lazy com.beworking.contacts.ContactProfileService contactProfileService,
                               StripeTaxSyncClient stripeTaxSyncClient) {
        this.subscriptionRepository = subscriptionRepository;
        this.cuentaService = cuentaService;
        this.jdbcTemplate = jdbcTemplate;
        this.viesVatService = viesVatService;
        this.contactProfileService = contactProfileService;
        this.stripeTaxSyncClient = stripeTaxSyncClient;
    }

    public List<Subscription> findAll() {
        return subscriptionRepository.findAll();
    }

    public int updateContactStatusIfTrial(Long contactId, String newStatus) {
        return jdbcTemplate.update(
            "UPDATE beworking.contact_profiles SET status = ?, status_changed_at = NOW() WHERE id = ? AND status = 'Trial'",
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
        return subscriptionRepository.findByActiveTrueAndProductoIdIsNotNull();
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

    public void deactivate(Integer id) {
        subscriptionRepository.findById(id).ifPresent(sub -> {
            sub.setActive(false);
            sub.setEndDate(LocalDate.now());
            subscriptionRepository.save(sub);
        });
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
                    }
                    logger.info("Invoice already exists for stripeInvoiceId={}, skipping creation",
                        payload.getStripeInvoiceId());
                    return null;
                }
            } catch (EmptyResultDataAccessException ignored) {
            }
        }

        // Resolve cuenta
        String cuentaCodigo = subscription.getCuenta();
        Integer cuentaId = null;
        Optional<Cuenta> cuentaOpt = cuentaService.getCuentaByCodigo(cuentaCodigo);
        if (cuentaOpt.isPresent()) {
            cuentaId = cuentaOpt.get().getId();
        }

        // Generate invoice number — reuse the one already reserved during invoice.created
        String invoiceNumber;
        if (payload.getStripeInvoiceNumber() != null && !payload.getStripeInvoiceNumber().isBlank()) {
            invoiceNumber = payload.getStripeInvoiceNumber();
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
                holdedinvoicenum, holdedinvoicepdf
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, ?, ?, ?, ?, ?)
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
            invoicePdf != null && !invoicePdf.isBlank() ? invoicePdf : null
        );

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

        // VAT calculation
        BigDecimal subtotal = subscription.getMonthlyAmount();
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
                holdedinvoicenum
            ) VALUES (?, ?, ?, ?, ?, ?, 'Pendiente', ?, ?, ?, ?, CURRENT_TIMESTAMP, ?)
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
            invoiceNumber
        );

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
    int resolveVatPercent(Subscription subscription) {
        // Lock-in path: stored value wins.
        if (subscription.getVatPercent() != null) {
            return subscription.getVatPercent();
        }
        // Legacy path: sub has no locked rate, compute from contact data.
        int resolved = computeFreshVatPercent(subscription);
        logger.info("VAT resolved for sub {} (legacy fallback — no locked vat_percent): {}%",
            subscription.getId(), resolved);
        return resolved;
    }

    /**
     * Always computes the VAT rate from scratch, ignoring any stored
     * vat_percent. Used by:
     *   - The legacy fallback above (subs created before the lock-in).
     *   - The admin "Re-validate VAT" endpoint, which deliberately recomputes
     *     after a fresh VIES check.
     */
    private int computeFreshVatPercent(Subscription subscription) {
        int fallback = 21;

        contactProfileService.ensureVatValidated(subscription.getContactId());

        String taxId = null;
        String billingCountry = null;
        Boolean vatValid = null;
        try {
            Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT billing_tax_id, billing_country, vat_valid FROM beworking.contact_profiles WHERE id = ?",
                subscription.getContactId());
            taxId = (String) row.get("billing_tax_id");
            billingCountry = (String) row.get("billing_country");
            vatValid = (Boolean) row.get("vat_valid");
        } catch (EmptyResultDataAccessException ignored) {}

        String cuenta = subscription.getCuenta() != null ? subscription.getCuenta().toUpperCase() : "PT";
        String supplierCountry = "GT".equals(cuenta) ? "EE" : "ES";

        String customerCountry = deriveCustomerCountry(taxId, billingCountry);
        if (customerCountry == null) return fallback;

        boolean reverseCharge = Boolean.TRUE.equals(vatValid) && !supplierCountry.equals(customerCountry);
        return reverseCharge ? 0 : EU_VAT_RATES.getOrDefault(customerCountry, fallback);
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
    public java.util.Map<String, Integer> bulkSyncStripeTax() {
        int processed = 0, synced = 0, skipped = 0;
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
            try {
                stripeTaxSyncClient.syncSubscriptionTax(
                    sub.getStripeSubscriptionId(), sub.getVatPercent(), sub.getCuenta());
                synced++;
            } catch (Exception e) {
                logger.warn("bulkSyncStripeTax: sub {} failed: {}", sub.getId(), e.getMessage());
            }
            // Stripe rate-limit safety: small delay between calls.
            try { Thread.sleep(200); } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return java.util.Map.of("processed", processed, "synced", synced, "skipped", skipped);
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
