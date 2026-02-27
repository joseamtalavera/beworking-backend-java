package com.beworking.subscriptions;

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

    public SubscriptionService(SubscriptionRepository subscriptionRepository,
                               CuentaService cuentaService,
                               JdbcTemplate jdbcTemplate) {
        this.subscriptionRepository = subscriptionRepository;
        this.cuentaService = cuentaService;
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<Subscription> findAll() {
        return subscriptionRepository.findAll();
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

        // Use Stripe subtotal when available (handles prorated first invoices correctly).
        // Fall back to subscription monthly amount for bank transfer or missing data.
        BigDecimal subtotal;
        if (payload.getSubtotalCents() != null && payload.getSubtotalCents() > 0) {
            subtotal = BigDecimal.valueOf(payload.getSubtotalCents())
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        } else {
            subtotal = subscription.getMonthlyAmount();
        }
        int vatPercent = subscription.getVatPercent() != null ? subscription.getVatPercent() : 21;
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
        int vatPercent = subscription.getVatPercent() != null ? subscription.getVatPercent() : 21;
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

}
