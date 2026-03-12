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

    public SubscriptionService(SubscriptionRepository subscriptionRepository,
                               CuentaService cuentaService,
                               JdbcTemplate jdbcTemplate,
                               ViesVatService viesVatService) {
        this.subscriptionRepository = subscriptionRepository;
        this.cuentaService = cuentaService;
        this.jdbcTemplate = jdbcTemplate;
        this.viesVatService = viesVatService;
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
            "SELECT COALESCE(MAX(id), 0) + 1 FROM beworking.facturas", Long.class);

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
            "SELECT COALESCE(MAX(id), 0) + 1 FROM beworking.facturasdesglose", Long.class);

        jdbcTemplate.update(
            """
            INSERT INTO beworking.facturasdesglose (
                id, idfacturadesglose, conceptodesglose, precioundesglose,
                cantidaddesglose, totaldesglose, desgloseconfirmado, idbloqueovinculado
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """,
            nextDesgloseId,
            invoiceId,
            description,
            subtotal,
            BigDecimal.ONE,
            subtotal,
            1,
            null
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
            "SELECT COALESCE(MAX(id), 0) + 1 FROM beworking.facturas", Long.class);

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
            "SELECT COALESCE(MAX(id), 0) + 1 FROM beworking.facturasdesglose", Long.class);

        jdbcTemplate.update(
            """
            INSERT INTO beworking.facturasdesglose (
                id, idfacturadesglose, conceptodesglose, precioundesglose,
                cantidaddesglose, totaldesglose, desgloseconfirmado, idbloqueovinculado
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """,
            nextDesgloseId,
            invoiceId,
            description,
            subtotal,
            BigDecimal.ONE,
            subtotal,
            1,
            null
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
        "AT","BE","BG","CY","CZ","DE","DK","EE","ES","FI","FR","GR",
        "HR","HU","IE","IT","LT","LU","LV","MT","NL","PL","PT","RO",
        "SE","SI","SK"
    );

    /**
     * Re-evaluates the correct VAT percentage from the contact's current billing
     * data and the subscription's cuenta, applying intra-EU reverse charge rules.
     * Also updates the subscription record if the resolved value differs.
     */
    int resolveVatPercent(Subscription subscription) {
        int fallback = subscription.getVatPercent() != null ? subscription.getVatPercent() : 21;

        String taxId = null;
        String billingCountry = null;
        try {
            Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT billing_tax_id, billing_country FROM beworking.contact_profiles WHERE id = ?",
                subscription.getContactId());
            taxId = (String) row.get("billing_tax_id");
            billingCountry = (String) row.get("billing_country");
        } catch (EmptyResultDataAccessException ignored) {}

        // Supplier country: GT = Estonia (EE), PT/OF = Spain (ES)
        String cuenta = subscription.getCuenta() != null ? subscription.getCuenta().toUpperCase() : "PT";
        String supplierCountry = "GT".equals(cuenta) ? "EE" : "ES";

        // Try to extract EU country prefix from taxId (e.g. "ESB09665258" → "ES")
        String customerCountry = null;
        if (taxId != null && !taxId.isBlank()) {
            String normalized = taxId.trim().replaceAll("\\s+", "").toUpperCase();
            if (normalized.length() >= 2 && EU_VAT_PREFIXES.contains(normalized.substring(0, 2))) {
                customerCountry = normalized.substring(0, 2);
            }
        }

        // No prefix found — try VIES with billing_country as hint (e.g. "B09665258" + "Spain" → "ESB09665258")
        if (customerCountry == null) {
            String countryHint = countryNameToIso(billingCountry);
            if (countryHint != null) {
                ViesVatService.VatValidationResult result = viesVatService.validate(taxId, countryHint);
                if (result.valid()) {
                    customerCountry = countryHint;
                    logger.info("VIES confirmed {} as {} for subscription {}",
                        taxId, countryHint, subscription.getId());
                } else {
                    logger.info("VIES could not validate {} (hint={}) for subscription {}: {}",
                        taxId, countryHint, subscription.getId(), result.error());
                }
            }
        }

        if (customerCountry == null || !EU_VAT_PREFIXES.contains(customerCountry)) {
            return fallback;
        }

        // Intra-EU reverse charge: 0% when supplier and customer are in different EU countries
        int resolved = supplierCountry.equals(customerCountry) ? fallback : 0;

        // Keep subscription record in sync
        if (subscription.getVatPercent() == null || subscription.getVatPercent() != resolved) {
            subscription.setVatPercent(resolved);
            subscriptionRepository.save(subscription);
            logger.info("Updated subscription {} vatPercent {} → {} (cuenta={}, taxId={}, customerCountry={})",
                subscription.getId(), fallback, resolved, cuenta, taxId, customerCountry);
        }

        return resolved;
    }

    private static String countryNameToIso(String countryName) {
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
            case "greece", "grecia" -> "GR";
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
