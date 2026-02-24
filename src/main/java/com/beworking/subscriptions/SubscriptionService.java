package com.beworking.subscriptions;

import com.beworking.cuentas.Cuenta;
import com.beworking.cuentas.CuentaService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
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

    public Optional<Subscription> findById(Integer id) {
        return subscriptionRepository.findById(id);
    }

    public Optional<Subscription> findByStripeSubscriptionId(String stripeSubscriptionId) {
        return subscriptionRepository.findByStripeSubscriptionId(stripeSubscriptionId);
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

        // Compute totals — use actual Stripe amounts when available (handles proration)
        BigDecimal subtotal;
        if (payload.getSubtotalCents() != null) {
            subtotal = new BigDecimal(payload.getSubtotalCents())
                .divide(new BigDecimal(100), 2, RoundingMode.HALF_UP);
        } else {
            subtotal = subscription.getMonthlyAmount();
        }
        int vatPercent = subscription.getVatPercent() != null ? subscription.getVatPercent() : 21;
        BigDecimal vatAmount;
        if (payload.getTaxCents() != null) {
            vatAmount = new BigDecimal(payload.getTaxCents())
                .divide(new BigDecimal(100), 2, RoundingMode.HALF_UP);
        } else {
            vatAmount = subtotal.multiply(BigDecimal.valueOf(vatPercent))
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        }
        BigDecimal total = subtotal.add(vatAmount);

        // Build description from period
        String periodLabel = buildPeriodLabel(payload.getPeriodStart());
        String description = subscription.getDescription() + " · " + periodLabel;

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

    private String buildPeriodLabel(String periodStart) {
        if (periodStart == null || periodStart.isBlank()) {
            return LocalDate.now().format(DateTimeFormatter.ofPattern("MMMM yyyy", new Locale("es", "ES")));
        }
        try {
            LocalDate date = LocalDate.parse(periodStart);
            return date.format(DateTimeFormatter.ofPattern("MMMM yyyy", new Locale("es", "ES")));
        } catch (Exception e) {
            return periodStart;
        }
    }
}
