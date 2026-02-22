package com.beworking.invoices;

import com.beworking.bookings.Bloqueo;
import com.beworking.bookings.BloqueoRepository;
import com.beworking.cuentas.CuentaService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.client.RestClient;

@Service
public class InvoiceService {

    private static final int MAX_PAGE_SIZE = 200;
    private static final DateTimeFormatter DAY_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE;

    private final JdbcTemplate jdbcTemplate;
    private final RestClient http;
    private final BloqueoRepository bloqueoRepository;
    private final CuentaService cuentaService;
    private final String paymentsBaseUrl;

    public InvoiceService(
            JdbcTemplate jdbcTemplate,
            BloqueoRepository bloqueoRepository,
            CuentaService cuentaService,
            @Value("${app.payments.base-url:}") String paymentsBaseUrl) {
        this.jdbcTemplate = jdbcTemplate;
        this.http = RestClient.create();
        this.bloqueoRepository = bloqueoRepository;
        this.cuentaService = cuentaService;
        this.paymentsBaseUrl = paymentsBaseUrl;
    }

    public Page<InvoiceListItem> findInvoices(int page, int size, InvoiceFilters filters) {
        int pageIndex = Math.max(page, 0);
        int pageSize = Math.max(1, Math.min(size, MAX_PAGE_SIZE));
        int offset = pageIndex * pageSize;

        String baseFrom = """
            FROM beworking.facturas f
            LEFT JOIN beworking.contact_profiles c ON c.id = f.idcliente
            LEFT JOIN beworking.facturasdesglose fd ON fd.idfacturadesglose = f.idfactura
            LEFT JOIN beworking.bloqueos b ON b.id = fd.idbloqueovinculado
            LEFT JOIN beworking.productos p ON p.id = b.id_producto
            """;

        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> args = new ArrayList<>();

        if (hasText(filters.name())) {
            String like = "%" + filters.name().trim().toLowerCase() + "%";
            where.append(" AND (unaccent(LOWER(COALESCE(c.name, ''))) LIKE unaccent(?)"
                + " OR unaccent(LOWER(COALESCE(c.contact_name, ''))) LIKE unaccent(?)"
                + " OR unaccent(LOWER(COALESCE(c.billing_name, ''))) LIKE unaccent(?))");
            args.add(like);
            args.add(like);
            args.add(like);
        }

        if (hasText(filters.email())) {
            String like = "%" + filters.email().trim().toLowerCase() + "%";
            where.append(" AND (LOWER(COALESCE(c.email_primary, '')) LIKE ?"
                + " OR LOWER(COALESCE(c.email_secondary, '')) LIKE ?"
                + " OR LOWER(COALESCE(c.email_tertiary, '')) LIKE ?"
                + " OR LOWER(COALESCE(c.representative_email, '')) LIKE ?)");
            args.add(like);
            args.add(like);
            args.add(like);
            args.add(like);
        }

        if (hasText(filters.idFactura())) {
            String like = "%" + filters.idFactura().trim().toLowerCase() + "%";
            where.append(" AND (CAST(f.idfactura AS TEXT) ILIKE ? OR CAST(f.id AS TEXT) ILIKE ?)");
            args.add(like);
            args.add(like);
        }

        if (hasText(filters.status())) {
            String like = "%" + filters.status().trim().toLowerCase() + "%";
            where.append(" AND LOWER(COALESCE(f.estado, '')) LIKE ?");
            args.add(like);
        }

        if (hasText(filters.tenantType())) {
            String like = "%" + filters.tenantType().trim().toLowerCase() + "%";
            where.append(" AND LOWER(COALESCE(c.tenant_type, '')) LIKE ?");
            args.add(like);
        }

        if (filters.contactId() != null) {
            where.append(" AND f.idcliente = ?");
            args.add(filters.contactId());
        }

        if (hasText(filters.product())) {
            String like = "%" + filters.product().trim().toLowerCase() + "%";
            where.append(" AND (LOWER(COALESCE(p.nombre, '')) LIKE ?"
                + " OR LOWER(COALESCE(fd.conceptodesglose, '')) LIKE ?)");
            args.add(like);
            args.add(like);
        }

        // Date filtering - use creacionfecha (creation date) for filtering
        if (hasText(filters.startDate())) {
            where.append(" AND f.creacionfecha >= ?::timestamp");
            args.add(filters.startDate().trim());
        }
        
        if (hasText(filters.endDate())) {
            where.append(" AND f.creacionfecha < ?::date + INTERVAL '1 day'");
            args.add(filters.endDate().trim());
        }

        List<Object> countArgs = new ArrayList<>(args);
        String countSql = "SELECT COUNT(DISTINCT f.id) " + baseFrom + where;
        long total;
        try {
            total = countArgs.isEmpty()
                ? jdbcTemplate.queryForObject(countSql, Long.class)
                : jdbcTemplate.queryForObject(countSql, countArgs.toArray(), Long.class);
        } catch (EmptyResultDataAccessException ignored) {
            total = 0L;
        }

        String dataSql = """
            SELECT
                f.id,
                f.idfactura,
                f.idcliente,
                f.idcentro,
                f.descripcion,
                f.total,
                f.iva,
                f.totaliva,
                f.estado,
                f.creacionfecha,
                f.holdedinvoicenum,
                f.holdedinvoicepdf,
                MAX(COALESCE(c.name, c.contact_name, c.billing_name)) AS client_name,
                MAX(
                    COALESCE(
                        c.email_primary,
                        c.email_secondary,
                        c.email_tertiary,
                        c.representative_email
                    )
                ) AS client_email,
                MAX(c.tenant_type) AS tenant_type,
                LEFT(STRING_AGG(DISTINCT COALESCE(
                    p.nombre,
                    CASE
                        WHEN fd.conceptodesglose LIKE '%Oficina Virtual:%' THEN
                            TRIM(SPLIT_PART(SPLIT_PART(fd.conceptodesglose, 'Oficina Virtual: ', 2), '.', 1))
                        WHEN fd.conceptodesglose LIKE '%Centro:%' THEN
                            TRIM(SPLIT_PART(SPLIT_PART(fd.conceptodesglose, 'Oficina Virtual: ', 2), '.', 1))
                        WHEN fd.conceptodesglose ~ '\\d{4}-\\d{2}-\\d{2}' THEN
                            TRIM(REGEXP_REPLACE(fd.conceptodesglose, '\\s+.\\s+\\d{4}-.*$', ''))
                        ELSE fd.conceptodesglose
                    END
                ), ', '), 500) AS products
            """ + baseFrom + where + " " + """
            GROUP BY
                f.id,
                f.idfactura,
                f.idcliente,
                f.idcentro,
                f.descripcion,
                f.total,
                f.iva,
                f.totaliva,
                f.estado,
                f.creacionfecha,
                f.holdedinvoicenum,
                f.holdedinvoicepdf
            ORDER BY f.creacionfecha DESC NULLS LAST, f.id DESC
            OFFSET ? LIMIT ?
            """;

        List<Object> dataArgs = new ArrayList<>(args);
        dataArgs.add(offset);
        dataArgs.add(pageSize);

        List<InvoiceListItem> items = jdbcTemplate.query(
            dataSql,
            dataArgs.toArray(),
            (rs, rowNum) -> {
                Long id = rs.getLong("id");
                Integer idFactura = rs.getObject("idfactura") != null ? rs.getInt("idfactura") : null;
                Long idCliente = rs.getObject("idcliente") != null ? rs.getLong("idcliente") : null;
                Integer idCentro = rs.getObject("idcentro") != null ? rs.getInt("idcentro") : null;
                Timestamp createdTs = rs.getTimestamp("creacionfecha");
                LocalDateTime createdAt = createdTs != null ? createdTs.toLocalDateTime() : null;
                return new InvoiceListItem(
                    id,
                    idFactura,
                    idCliente,
                    idCentro,
                    rs.getString("descripcion"),
                    rs.getBigDecimal("total"),
                    (Integer) rs.getObject("iva"),
                    rs.getBigDecimal("totaliva"),
                    rs.getString("estado"),
                    createdAt,
                    rs.getString("holdedinvoicenum"),
                    rs.getString("holdedinvoicepdf"),
                    rs.getString("client_name"),
                    rs.getString("client_email"),
                    rs.getString("tenant_type"),
                    rs.getString("products")
                );
            }
        );

        return new PageImpl<>(items, PageRequest.of(pageIndex, pageSize), total);
    }

    public BigDecimal calculateTotalRevenue(InvoiceFilters filters) {
        String baseFrom = """
            FROM beworking.facturas f
            LEFT JOIN beworking.contact_profiles c ON c.id = f.idcliente
            LEFT JOIN beworking.facturasdesglose fd ON fd.idfacturadesglose = f.idfactura
            LEFT JOIN beworking.bloqueos b ON b.id = fd.idbloqueovinculado
            LEFT JOIN beworking.productos p ON p.id = b.id_producto
            """;

        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> args = new ArrayList<>();

        if (hasText(filters.name())) {
            String like = "%" + filters.name().trim().toLowerCase() + "%";
            where.append(" AND (unaccent(LOWER(COALESCE(c.name, ''))) LIKE unaccent(?)"
                + " OR unaccent(LOWER(COALESCE(c.contact_name, ''))) LIKE unaccent(?)"
                + " OR unaccent(LOWER(COALESCE(c.billing_name, ''))) LIKE unaccent(?))");
            args.add(like);
            args.add(like);
            args.add(like);
        }

        if (hasText(filters.email())) {
            String like = "%" + filters.email().trim().toLowerCase() + "%";
            where.append(" AND (LOWER(COALESCE(c.email_primary, '')) LIKE ?"
                + " OR LOWER(COALESCE(c.email_secondary, '')) LIKE ?"
                + " OR LOWER(COALESCE(c.email_tertiary, '')) LIKE ?"
                + " OR LOWER(COALESCE(c.representative_email, '')) LIKE ?)");
            args.add(like);
            args.add(like);
            args.add(like);
            args.add(like);
        }

        if (hasText(filters.idFactura())) {
            String like = "%" + filters.idFactura().trim().toLowerCase() + "%";
            where.append(" AND (CAST(f.idfactura AS TEXT) ILIKE ? OR CAST(f.id AS TEXT) ILIKE ?)");
            args.add(like);
            args.add(like);
        }

        if (hasText(filters.status())) {
            String like = "%" + filters.status().trim().toLowerCase() + "%";
            where.append(" AND LOWER(COALESCE(f.estado, '')) LIKE ?");
            args.add(like);
        }

        if (hasText(filters.tenantType())) {
            String like = "%" + filters.tenantType().trim().toLowerCase() + "%";
            where.append(" AND LOWER(COALESCE(c.tenant_type, '')) LIKE ?");
            args.add(like);
        }

        if (filters.contactId() != null) {
            where.append(" AND f.idcliente = ?");
            args.add(filters.contactId());
        }

        if (hasText(filters.product())) {
            String like = "%" + filters.product().trim().toLowerCase() + "%";
            where.append(" AND (LOWER(COALESCE(p.nombre, '')) LIKE ?"
                + " OR LOWER(COALESCE(fd.conceptodesglose, '')) LIKE ?)");
            args.add(like);
            args.add(like);
        }

        // Date filtering - use creacionfecha (creation date) for filtering
        if (hasText(filters.startDate())) {
            where.append(" AND f.creacionfecha >= ?::timestamp");
            args.add(filters.startDate().trim());
        }
        
        if (hasText(filters.endDate())) {
            where.append(" AND f.creacionfecha < ?::date + INTERVAL '1 day'");
            args.add(filters.endDate().trim());
        }

        String revenueSql = """
            SELECT COALESCE(SUM(f.total), 0) 
            FROM (
                SELECT DISTINCT f.id, f.total
                FROM beworking.facturas f
                LEFT JOIN beworking.contact_profiles c ON c.id = f.idcliente
                LEFT JOIN beworking.facturasdesglose fd ON fd.idfacturadesglose = f.idfactura
                LEFT JOIN beworking.bloqueos b ON b.id = fd.idbloqueovinculado
                LEFT JOIN beworking.productos p ON p.id = b.id_producto
            """;
        
        // Append the WHERE conditions to the subquery
        revenueSql += where.toString() + ") f";
        
        try {
            BigDecimal totalRevenue = args.isEmpty()
                ? jdbcTemplate.queryForObject(revenueSql, BigDecimal.class)
                : jdbcTemplate.queryForObject(revenueSql, args.toArray(), BigDecimal.class);
            return totalRevenue != null ? totalRevenue : BigDecimal.ZERO;
        } catch (EmptyResultDataAccessException e) {
            return BigDecimal.ZERO;
        }
    }

    @Transactional
    public CreateInvoiceResponse createInvoice(CreateInvoiceRequest request) {
        if (request.getBloqueoIds() == null || request.getBloqueoIds().isEmpty()) {
            throw new IllegalArgumentException("At least one bloqueo must be provided.");
        }

        List<Bloqueo> bloqueos = bloqueoRepository.findAllById(request.getBloqueoIds());
        if (bloqueos.size() != request.getBloqueoIds().size()) {
            throw new IllegalArgumentException("Unable to locate all requested bloqueos.");
        }

        Bloqueo first = bloqueos.get(0);
        Long contactId = first.getCliente() != null ? first.getCliente().getId() : null;
        Integer centerId = first.getCentro() != null ? safeLongToInt(first.getCentro().getId()) : null;

        for (Bloqueo bloqueo : bloqueos) {
            Long bloqueoContact = bloqueo.getCliente() != null ? bloqueo.getCliente().getId() : null;
            if (!Objects.equals(contactId, bloqueoContact)) {
                throw new IllegalArgumentException("All bloqueos must belong to the same contact.");
            }
            Integer bloqueoCenter = bloqueo.getCentro() != null ? safeLongToInt(bloqueo.getCentro().getId()) : null;
            if (!Objects.equals(centerId, bloqueoCenter)) {
                throw new IllegalArgumentException("All bloqueos must belong to the same center.");
            }
            if (isInvoiced(bloqueo.getEstado())) {
                throw new IllegalStateException("Bloqueo " + bloqueo.getId() + " is already invoiced.");
            }
        }

        Map<Bloqueo, LineComputation> computedLines = new LinkedHashMap<>();
        BigDecimal subtotal = BigDecimal.ZERO;
        for (Bloqueo bloqueo : bloqueos) {
            LineComputation line = computeLine(bloqueo);
            computedLines.put(bloqueo, line);
            if (line.total() != null) {
                subtotal = subtotal.add(line.total());
            }
        }

        // Add extra line items to subtotal
        List<CreateInvoiceRequest.ExtraLineItem> extras = request.getExtraLineItems();
        if (extras != null) {
            for (CreateInvoiceRequest.ExtraLineItem extra : extras) {
                if (extra.getDescription() != null && !extra.getDescription().isBlank()) {
                    BigDecimal lineTotal = extra.getQuantity().multiply(extra.getPrice())
                        .setScale(2, RoundingMode.HALF_UP);
                    subtotal = subtotal.add(lineTotal);
                }
            }
        }

        subtotal = subtotal.setScale(2, RoundingMode.HALF_UP);

        BigDecimal vatPercent = request.getVatPercent();
        if (vatPercent != null) {
            vatPercent = vatPercent.setScale(2, RoundingMode.HALF_UP);
        }
        BigDecimal vatAmount = vatPercent != null
            ? subtotal.multiply(vatPercent).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP)
            : BigDecimal.ZERO;
        BigDecimal total = subtotal.add(vatAmount);

        Long nextId = jdbcTemplate.queryForObject("SELECT COALESCE(MAX(id), 0) + 1 FROM beworking.facturas", Long.class);
        Integer nextLegacy = jdbcTemplate.queryForObject("SELECT COALESCE(MAX(idfactura), 0) + 1 FROM beworking.facturas", Integer.class);
        LocalDateTime now = LocalDateTime.now();

        String description = request.getDescription();
        if (description == null || description.isBlank()) {
            description = buildDefaultDescription(bloqueos);
        }

        jdbcTemplate.update(
            """
            INSERT INTO beworking.facturas
            (id, idfactura, idcliente, idcentro, descripcion, total, iva, totaliva, estado, creacionfecha, holdedinvoicenum, holdedinvoicepdf)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            nextId,
            nextLegacy,
            contactId,
            centerId,
            description,
            subtotal,
            vatPercent != null ? vatPercent.intValue() : null,
            total,
            "Pendiente",
            Timestamp.valueOf(now),
            request.getReference(),
            null
        );

        long nextDesgloseId = jdbcTemplate.queryForObject("SELECT COALESCE(MAX(id), 0) + 1 FROM beworking.facturasdesglose", Long.class);

        for (Map.Entry<Bloqueo, LineComputation> entry : computedLines.entrySet()) {
            Bloqueo bloqueo = entry.getKey();
            LineComputation line = entry.getValue();
            jdbcTemplate.update(
                """
                INSERT INTO beworking.facturasdesglose
                (id, idfacturadesglose, conceptodesglose, precioundesglose, cantidaddesglose, totaldesglose, desgloseconfirmado, idbloqueovinculado)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                nextDesgloseId,
                nextLegacy,
                line.concept(),
                line.unitPrice(),
                line.quantity(),
                line.total(),
                1,
                bloqueo.getId()
            );
            nextDesgloseId++;

            bloqueo.setEstado("Invoiced");
            bloqueo.setEdicionFecha(now);
        }

        // Insert extra line items (not linked to any bloqueo)
        if (extras != null) {
            for (CreateInvoiceRequest.ExtraLineItem extra : extras) {
                if (extra.getDescription() != null && !extra.getDescription().isBlank()) {
                    BigDecimal lineTotal = extra.getQuantity().multiply(extra.getPrice())
                        .setScale(2, RoundingMode.HALF_UP);
                    jdbcTemplate.update(
                        """
                        INSERT INTO beworking.facturasdesglose
                        (id, idfacturadesglose, conceptodesglose, precioundesglose, cantidaddesglose, totaldesglose, desgloseconfirmado, idbloqueovinculado)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                        nextDesgloseId,
                        nextLegacy,
                        extra.getDescription(),
                        extra.getPrice().setScale(2, RoundingMode.HALF_UP),
                        extra.getQuantity(),
                        lineTotal,
                        1,
                        null
                    );
                    nextDesgloseId++;
                }
            }
        }

        bloqueoRepository.saveAll(bloqueos);

        List<CreateInvoiceResponse.Line> responseLines = computedLines.entrySet().stream()
            .map(entry -> new CreateInvoiceResponse.Line(
                entry.getKey().getId(),
                entry.getKey().getReserva() != null ? entry.getKey().getReserva().getId() : null,
                entry.getValue().concept(),
                entry.getValue().quantity(),
                entry.getValue().unitPrice(),
                entry.getValue().total()
            ))
            .toList();

        return new CreateInvoiceResponse(
            nextId,
            nextLegacy,
            contactId,
            centerId,
            description,
            subtotal,
            vatPercent,
            vatAmount,
            total,
            "Pendiente",
            now,
            responseLines
        );
    }

    public Optional<URI> resolvePdfUrl(Long id) {
        Map<String, Object> row = jdbcTemplate.queryForMap(
            "SELECT idfactura, holdedinvoicepdf FROM beworking.facturas WHERE id = ?",
            id
        );
        Integer idFactura = (Integer) row.get("idfactura");
        String stored = (String) row.get("holdedinvoicepdf");

        if (stored != null && (stored.startsWith("http://") || stored.startsWith("https://"))) {
            return Optional.of(URI.create(stored));
        }

        if (idFactura == null) {
            return Optional.empty();
        }

        String file = "Factura-" + idFactura + ".pdf";
        List<String> bases = Arrays.asList(
            "https://app.manaager.com/filesbeworking",
            "https://app.be-working.com/filesbeworking",
            "https://be-working.com/filesbeworking"
        );

        for (String base : bases) {
            URI candidate = URI.create(base + "/" + file);
            try {
                ResponseEntity<Void> resp = http.method(HttpMethod.HEAD)
                    .uri(candidate)
                    .retrieve()
                    .toBodilessEntity();
                if (resp.getStatusCode().is2xxSuccessful()) {
                    return Optional.of(candidate);
                }
            } catch (Exception ignored) {
            }
        }

        return Optional.empty();
    }

    @Transactional
    public int markInvoicePaid(String reference, String stripePaymentIntentId, String stripeInvoiceId) {
        // Try matching by stripeinvoiceid first (most reliable for Stripe Invoice payments)
        if (stripeInvoiceId != null && !stripeInvoiceId.isBlank()) {
            int updated = jdbcTemplate.update(
                "UPDATE beworking.facturas SET estado = 'Pagado', stripepaymentintentid1 = ?, stripepaymentintentstatus1 = 'succeeded' WHERE stripeinvoiceid = ? AND estado <> 'Pagado'",
                stripePaymentIntentId, stripeInvoiceId
            );
            if (updated > 0) {
                return updated;
            }
        }

        if (reference == null || reference.isBlank()) {
            return 0;
        }

        // Try matching by holdedinvoicenum (the stored reference)
        int updated = jdbcTemplate.update(
            "UPDATE beworking.facturas SET estado = 'Pagado', stripepaymentintentid1 = ?, stripepaymentintentstatus1 = 'succeeded' WHERE holdedinvoicenum = ? AND estado <> 'Pagado'",
            stripePaymentIntentId, reference
        );
        if (updated > 0) {
            return updated;
        }

        // Try matching by idfactura (numeric invoice number)
        try {
            int numRef = Integer.parseInt(reference);
            updated = jdbcTemplate.update(
                "UPDATE beworking.facturas SET estado = 'Pagado', stripepaymentintentid1 = ?, stripepaymentintentstatus1 = 'succeeded' WHERE idfactura = ? AND estado <> 'Pagado'",
                stripePaymentIntentId, numRef
            );
        } catch (NumberFormatException ignored) {
        }

        return updated;
    }

    @Transactional
    public Map<String, Object> updateInvoiceStatus(Long id, String status) {
        String normalized = normalizeInvoiceStatus(status);
        int updated = jdbcTemplate.update(
            "UPDATE beworking.facturas SET estado = ? WHERE id = ?", normalized, id);
        if (updated == 0) {
            throw new IllegalArgumentException("Invoice not found: " + id);
        }
        Map<String, Object> response = new HashMap<>();
        response.put("id", id);
        response.put("status", normalized);
        return response;
    }

    public Map<String, Object> getInvoiceDetail(Long id) {
        Map<String, Object> invoice;
        try {
            invoice = jdbcTemplate.queryForMap(
                """
                SELECT f.id, f.idfactura, f.idcliente, f.idcentro, f.descripcion,
                       f.total, f.iva, f.totaliva, f.estado, f.creacionfecha,
                       f.holdedinvoicenum, f.notas, f.holdedcuenta, f.id_cuenta,
                       f.fechacreacionreal, f.fechacobro1,
                       c.name AS client_name, c.tenant_type
                FROM beworking.facturas f
                LEFT JOIN beworking.contact_profiles c ON c.id = f.idcliente
                WHERE f.id = ?
                """, id);
        } catch (EmptyResultDataAccessException e) {
            throw new IllegalArgumentException("Invoice not found: " + id);
        }

        Integer idfactura = (Integer) invoice.get("idfactura");
        List<Map<String, Object>> lines = jdbcTemplate.queryForList(
            """
            SELECT conceptodesglose, precioundesglose, cantidaddesglose, totaldesglose
            FROM beworking.facturasdesglose WHERE idfacturadesglose = ?
            """, idfactura);

        invoice.put("lineItems", lines);
        return invoice;
    }

    @Transactional
    public Map<String, Object> updateInvoice(Long id, CreateManualInvoiceRequest request) {
        // Verify the invoice exists
        Map<String, Object> existing;
        try {
            existing = jdbcTemplate.queryForMap(
                "SELECT id, idfactura FROM beworking.facturas WHERE id = ?", id);
        } catch (EmptyResultDataAccessException e) {
            throw new IllegalArgumentException("Invoice not found: " + id);
        }

        Integer idfactura = (Integer) existing.get("idfactura");

        // Get the cuenta ID from the codigo
        Integer cuentaId = null;
        if (request.getCuenta() != null && !request.getCuenta().isEmpty()) {
            Optional<com.beworking.cuentas.Cuenta> cuentaOpt = cuentaService.getCuentaByCodigo(request.getCuenta());
            if (cuentaOpt.isPresent()) {
                cuentaId = cuentaOpt.get().getId();
            }
        }

        String normalizedStatus = normalizeInvoiceStatus(request.getStatus());

        // Update the main invoice record
        jdbcTemplate.update(
            """
            UPDATE beworking.facturas SET
                idcliente = ?, holdedcuenta = ?, id_cuenta = ?,
                fechacreacionreal = ?, fechacobro1 = ?, estado = ?,
                total = ?, iva = ?, totaliva = ?, notas = ?
            WHERE id = ?
            """,
            request.getClientId(),
            request.getCuenta(),
            cuentaId,
            request.getDate(),
            request.getDueDate(),
            normalizedStatus,
            request.getComputed().getTotal(),
            request.getComputed().getTotalVat().intValue(),
            request.getComputed().getTotalVat(),
            request.getNote(),
            id
        );

        // Delete existing line items and re-insert
        jdbcTemplate.update(
            "DELETE FROM beworking.facturasdesglose WHERE idfacturadesglose = ?", idfactura);

        if (request.getLineItems() != null && !request.getLineItems().isEmpty()) {
            Long nextDesgloseId = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(id), 0) + 1 FROM beworking.facturasdesglose", Long.class);

            for (int i = 0; i < request.getLineItems().size(); i++) {
                CreateManualInvoiceRequest.LineItem item = request.getLineItems().get(i);
                BigDecimal unitPrice = item.getPrice().setScale(2, RoundingMode.HALF_UP);
                BigDecimal lineTotal = unitPrice.multiply(item.getQuantity())
                    .multiply(BigDecimal.ONE.add(item.getVatPercent().divide(BigDecimal.valueOf(100))))
                    .setScale(2, RoundingMode.HALF_UP);

                String description = item.getDescription();
                if (description == null || description.isBlank()) {
                    description = "Line item";
                }

                jdbcTemplate.update(
                    """
                    INSERT INTO beworking.facturasdesglose
                    (id, idfacturadesglose, conceptodesglose, precioundesglose, cantidaddesglose, totaldesglose, desgloseconfirmado)
                    VALUES (?, ?, ?, ?, ?, ?, 1)
                    """,
                    nextDesgloseId + i, idfactura,
                    description, unitPrice, item.getQuantity(), lineTotal
                );
            }
        }

        Map<String, Object> response = new HashMap<>();
        response.put("id", id);
        response.put("idFactura", idfactura);
        response.put("message", "Invoice updated successfully");
        response.put("status", normalizedStatus);
        return response;
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private static boolean isInvoiced(String estado) {
        if (estado == null) {
            return false;
        }
        String value = estado.trim().toLowerCase();
        return value.contains("invoice") || value.contains("factura") || value.contains("pend") || value.contains("pag");
    }

    private static String normalizeInvoiceStatus(String status) {
        if (status == null) {
            return "Pendiente";
        }
        String value = status.trim().toLowerCase();
        if (value.contains("pag")) {
            return "Pagado";
        }
        return "Pendiente";
    }

    private LineComputation computeLine(Bloqueo bloqueo) {
        String productName = bloqueo.getProducto() != null ? bloqueo.getProducto().getNombre() : null;
        String centerName = bloqueo.getCentro() != null ? bloqueo.getCentro().getNombre() : null;
        String concept = buildConcept(productName, centerName, bloqueo);

        BigDecimal quantity = BigDecimal.ONE;
        if (bloqueo.getFechaIni() != null && bloqueo.getFechaFin() != null) {
            long hours = Duration.between(bloqueo.getFechaIni(), bloqueo.getFechaFin()).toHours();
            if (hours > 0) {
                quantity = BigDecimal.valueOf(hours);
            }
        }

        Double tarifa = bloqueo.getTarifa();
        BigDecimal unitPrice = tarifa != null
            ? BigDecimal.valueOf(tarifa).setScale(2, RoundingMode.HALF_UP)
            : BigDecimal.ZERO;
        BigDecimal lineTotal = unitPrice.multiply(quantity).setScale(2, RoundingMode.HALF_UP);

        return new LineComputation(concept, quantity, unitPrice, lineTotal);
    }

    private static String buildConcept(String productName, String centerName, Bloqueo bloqueo) {
        if (productName != null && !productName.isBlank()) {
            return productName;
        }
        return "Workspace booking";
    }

    private static String buildDefaultDescription(List<Bloqueo> bloqueos) {
        if (bloqueos.isEmpty()) {
            return "Generated invoice";
        }
        Set<String> products = bloqueos.stream()
            .map(b -> b.getProducto() != null ? b.getProducto().getNombre() : null)
            .filter(name -> name != null && !name.isBlank())
            .collect(Collectors.toSet());
        String productSummary = products.isEmpty() ? "Reservation" : String.join(", ", products);

        LocalDate min = bloqueos.stream()
            .map(Bloqueo::getFechaIni)
            .filter(Objects::nonNull)
            .map(LocalDateTime::toLocalDate)
            .min(LocalDate::compareTo)
            .orElse(null);
        LocalDate max = bloqueos.stream()
            .map(Bloqueo::getFechaFin)
            .filter(Objects::nonNull)
            .map(LocalDateTime::toLocalDate)
            .max(LocalDate::compareTo)
            .orElse(min);

        if (min != null && max != null) {
            if (min.equals(max)) {
                return productSummary + " · " + DAY_FORMAT.format(min);
            }
            return productSummary + " · " + DAY_FORMAT.format(min) + " - " + DAY_FORMAT.format(max);
        }
        return productSummary + " bloqueos";
    }

    private static Integer safeLongToInt(Long value) {
        if (value == null) {
            return null;
        }
        if (value > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Value exceeds integer range: " + value);
        }
        return value.intValue();
    }

    public record InvoiceFilters(
        String name,
        String email,
        String idFactura,
        String status,
        String tenantType,
        String product,
        String startDate,
        String endDate,
        Long contactId
    ) { }

    public Optional<Long> findContactIdByEmail(String email) {
        if (!hasText(email)) {
            return Optional.empty();
        }
        String sql = """
            SELECT id
            FROM beworking.contact_profiles
            WHERE LOWER(email_primary) = LOWER(?)
               OR LOWER(email_secondary) = LOWER(?)
               OR LOWER(email_tertiary) = LOWER(?)
               OR LOWER(representative_email) = LOWER(?)
            LIMIT 1
            """;
        try {
            Long id = jdbcTemplate.queryForObject(sql, Long.class, email, email, email, email);
            return Optional.ofNullable(id);
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }

    @Transactional
    public Map<String, Object> creditInvoice(Long originalId) {
        // Load original invoice
        Map<String, Object> original;
        try {
            original = jdbcTemplate.queryForMap(
                "SELECT id, idfactura, idcliente, idcentro, descripcion, total, iva, totaliva, estado,"
                    + " holdedcuenta, id_cuenta, holdedinvoicenum,"
                    + " stripepaymentintentid1, stripepaymentintentstatus1, stripeinvoiceid"
                    + " FROM beworking.facturas WHERE id = ?",
                originalId
            );
        } catch (EmptyResultDataAccessException e) {
            throw new IllegalArgumentException("Invoice not found: " + originalId);
        }

        Integer origLegacy = (Integer) original.get("idfactura");
        Long origClientId = original.get("idcliente") != null ? ((Number) original.get("idcliente")).longValue() : null;
        Integer origCenterId = original.get("idcentro") != null ? ((Number) original.get("idcentro")).intValue() : null;
        BigDecimal origTotal = (BigDecimal) original.get("total");
        BigDecimal origTotalIva = (BigDecimal) original.get("totaliva");
        Integer origIva = original.get("iva") != null ? ((Number) original.get("iva")).intValue() : null;
        String origCuenta = (String) original.get("holdedcuenta");
        Integer origCuentaId = original.get("id_cuenta") != null ? ((Number) original.get("id_cuenta")).intValue() : null;
        String origInvoiceNum = original.get("holdedinvoicenum") != null
            ? (String) original.get("holdedinvoicenum")
            : (origLegacy != null ? origLegacy.toString() : originalId.toString());

        // Load original line items
        List<Map<String, Object>> origLines = jdbcTemplate.queryForList(
            "SELECT conceptodesglose, precioundesglose, cantidaddesglose, totaldesglose"
                + " FROM beworking.facturasdesglose WHERE idfacturadesglose = ?",
            origLegacy
        );

        // Generate new IDs
        Long nextId = jdbcTemplate.queryForObject("SELECT COALESCE(MAX(id), 0) + 1 FROM beworking.facturas", Long.class);
        Integer nextLegacy = jdbcTemplate.queryForObject("SELECT COALESCE(MAX(idfactura), 0) + 1 FROM beworking.facturas", Integer.class);

        // Negate totals
        BigDecimal creditTotal = origTotal != null ? origTotal.negate() : BigDecimal.ZERO;
        BigDecimal creditTotalIva = origTotalIva != null ? origTotalIva.negate() : BigDecimal.ZERO;
        String description = "Rectificación de Factura #" + origInvoiceNum;

        // Insert credit note
        jdbcTemplate.update(
            """
            INSERT INTO beworking.facturas
            (id, idfactura, idcliente, idcentro, descripcion, total, iva, totaliva, estado,
             creacionfecha, holdedcuenta, id_cuenta, holdedinvoicenum)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'Pagado', CURRENT_TIMESTAMP, ?, ?, ?)
            """,
            nextId, nextLegacy, origClientId, origCenterId, description,
            creditTotal, origIva, creditTotalIva,
            origCuenta, origCuentaId, null
        );

        // Insert negated line items
        if (!origLines.isEmpty()) {
            Long nextDesgloseId = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(id), 0) + 1 FROM beworking.facturasdesglose", Long.class);

            for (int i = 0; i < origLines.size(); i++) {
                Map<String, Object> line = origLines.get(i);
                String concept = (String) line.get("conceptodesglose");
                BigDecimal unitPrice = line.get("precioundesglose") != null
                    ? ((BigDecimal) line.get("precioundesglose")).negate() : BigDecimal.ZERO;
                BigDecimal qty = line.get("cantidaddesglose") != null
                    ? (BigDecimal) line.get("cantidaddesglose") : BigDecimal.ONE;
                BigDecimal lineTotal = line.get("totaldesglose") != null
                    ? ((BigDecimal) line.get("totaldesglose")).negate() : BigDecimal.ZERO;

                jdbcTemplate.update(
                    """
                    INSERT INTO beworking.facturasdesglose
                    (id, idfacturadesglose, conceptodesglose, precioundesglose, cantidaddesglose, totaldesglose, desgloseconfirmado)
                    VALUES (?, ?, ?, ?, ?, ?, 1)
                    """,
                    nextDesgloseId + i, nextLegacy,
                    "Rectificación: " + (concept != null ? concept : "—"),
                    unitPrice, qty, lineTotal
                );
            }
        }

        // Mark original as rectified
        jdbcTemplate.update(
            "UPDATE beworking.facturas SET estado = 'Rectificado' WHERE id = ?",
            originalId
        );

        // Stripe: refund if paid, void invoice if not paid
        String stripeRefundId = null;
        String stripeVoidedInvoiceId = null;
        String stripePaymentIntentId = (String) original.get("stripepaymentintentid1");
        String stripePaymentStatus = (String) original.get("stripepaymentintentstatus1");
        String stripeInvoiceId = (String) original.get("stripeinvoiceid");

        if (paymentsBaseUrl != null && !paymentsBaseUrl.isBlank()) {
            // Case 1: Paid via Stripe → refund
            if (stripePaymentIntentId != null && !stripePaymentIntentId.isBlank()
                    && "succeeded".equalsIgnoreCase(stripePaymentStatus)) {
                try {
                    Map<String, Object> refundBody = new HashMap<>();
                    refundBody.put("payment_intent_id", stripePaymentIntentId);
                    @SuppressWarnings("unchecked")
                    Map<String, Object> refundResult = http.post()
                        .uri(paymentsBaseUrl + "/api/refunds")
                        .header("Content-Type", "application/json")
                        .body(refundBody)
                        .retrieve()
                        .body((Class<Map<String, Object>>) (Class<?>) Map.class);
                    if (refundResult != null) {
                        stripeRefundId = (String) refundResult.get("refundId");
                    }
                } catch (Exception e) {
                    System.err.println("Stripe refund failed for PI " + stripePaymentIntentId + ": " + e.getMessage());
                }
            }
            // Case 2: Stripe Invoice sent but not paid → void it
            else if (stripeInvoiceId != null && !stripeInvoiceId.isBlank()) {
                try {
                    Map<String, Object> voidBody = new HashMap<>();
                    voidBody.put("invoice_id", stripeInvoiceId);
                    @SuppressWarnings("unchecked")
                    Map<String, Object> voidResult = http.post()
                        .uri(paymentsBaseUrl + "/api/void-invoice")
                        .header("Content-Type", "application/json")
                        .body(voidBody)
                        .retrieve()
                        .body((Class<Map<String, Object>>) (Class<?>) Map.class);
                    if (voidResult != null) {
                        stripeVoidedInvoiceId = (String) voidResult.get("invoiceId");
                    }
                } catch (Exception e) {
                    System.err.println("Stripe void invoice failed for " + stripeInvoiceId + ": " + e.getMessage());
                }
            }
        }

        Map<String, Object> response = new HashMap<>();
        response.put("id", nextId);
        response.put("idFactura", nextLegacy);
        response.put("holdedInvoiceNum", null);
        response.put("message", "Credit note created for invoice #" + origInvoiceNum);
        if (stripeRefundId != null) {
            response.put("stripeRefundId", stripeRefundId);
        }
        if (stripeVoidedInvoiceId != null) {
            response.put("stripeVoidedInvoiceId", stripeVoidedInvoiceId);
        }
        return response;
    }

    /**
     * Find bloqueos marked as invoiced but with no corresponding facturasdesglose record.
     */
    public List<Map<String, Object>> findOrphanedInvoicedBloqueos() {
        String sql = """
            SELECT b.id, b.estado, b.id_cliente, b.id_centro, b.id_producto,
                   b.fecha_ini, b.fecha_fin, b.tarifa,
                   c.name AS client_name,
                   p.nombre AS product_name,
                   centro.nombre AS center_name,
                   EXTRACT(EPOCH FROM (b.fecha_fin - b.fecha_ini))/3600 AS hours
            FROM beworking.bloqueos b
            LEFT JOIN beworking.contact_profiles c ON c.id = b.id_cliente
            LEFT JOIN beworking.productos p ON p.id = b.id_producto
            LEFT JOIN beworking.centros centro ON centro.id = b.id_centro
            WHERE (LOWER(b.estado) LIKE '%invoice%' OR LOWER(b.estado) LIKE '%factura%')
              AND b.id NOT IN (
                  SELECT fd.idbloqueovinculado
                  FROM beworking.facturasdesglose fd
                  WHERE fd.idbloqueovinculado IS NOT NULL
              )
            ORDER BY b.fecha_ini DESC
            """;
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql);

        // Enrich each row with the estimated rate that would be used by the fix
        for (Map<String, Object> row : rows) {
            Double tarifa = row.get("tarifa") != null ? ((Number) row.get("tarifa")).doubleValue() : null;
            Long contactId = row.get("id_cliente") != null ? ((Number) row.get("id_cliente")).longValue() : null;
            Long productId = row.get("id_producto") != null ? ((Number) row.get("id_producto")).longValue() : null;

            if (tarifa != null) {
                row.put("estimated_rate", tarifa);
                row.put("rate_source", "bloqueo");
            } else if (contactId != null && productId != null) {
                Double historicalRate = lookupHistoricalRate(contactId, productId);
                row.put("estimated_rate", historicalRate);
                row.put("rate_source", historicalRate != null ? "historical" : "none");
            } else {
                row.put("estimated_rate", null);
                row.put("rate_source", "none");
            }
        }
        return rows;
    }

    /**
     * Create missing facturas for orphaned invoiced bloqueos.
     * Each bloqueo gets its own factura with estado = "Pagado".
     * When tarifa is null, looks up the contact's most recent rate for the same product.
     */
    @Transactional
    public List<Map<String, Object>> fixOrphanedInvoicedBloqueos() {
        List<Map<String, Object>> orphaned = findOrphanedInvoicedBloqueos();
        if (orphaned.isEmpty()) {
            return List.of();
        }

        List<Map<String, Object>> created = new ArrayList<>();

        for (Map<String, Object> row : orphaned) {
            Long bloqueoId = ((Number) row.get("id")).longValue();
            Long contactId = row.get("id_cliente") != null ? ((Number) row.get("id_cliente")).longValue() : null;
            Integer centerId = row.get("id_centro") != null ? ((Number) row.get("id_centro")).intValue() : null;
            Long productId = row.get("id_producto") != null ? ((Number) row.get("id_producto")).longValue() : null;
            String productName = (String) row.get("product_name");
            String clientName = (String) row.get("client_name");
            Double tarifa = row.get("tarifa") != null ? ((Number) row.get("tarifa")).doubleValue() : null;

            // If tarifa is null, look up the contact's most recent rate for the same product
            String rateSource = "bloqueo";
            if (tarifa == null && contactId != null && productId != null) {
                tarifa = lookupHistoricalRate(contactId, productId);
                rateSource = tarifa != null ? "historical" : "none";
            } else if (tarifa == null) {
                rateSource = "none";
            }

            Timestamp fechaIniTs = (Timestamp) row.get("fecha_ini");
            Timestamp fechaFinTs = (Timestamp) row.get("fecha_fin");
            LocalDateTime fechaIni = fechaIniTs != null ? fechaIniTs.toLocalDateTime() : null;
            LocalDateTime fechaFin = fechaFinTs != null ? fechaFinTs.toLocalDateTime() : null;

            // Compute line — use fractional hours for sub-hour bookings
            BigDecimal quantity = BigDecimal.ONE;
            if (fechaIni != null && fechaFin != null) {
                long minutes = Duration.between(fechaIni, fechaFin).toMinutes();
                if (minutes > 0) {
                    quantity = BigDecimal.valueOf(minutes)
                        .divide(BigDecimal.valueOf(60), 4, RoundingMode.HALF_UP);
                }
            }
            BigDecimal unitPrice = tarifa != null
                ? BigDecimal.valueOf(tarifa).setScale(2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
            BigDecimal lineTotal = unitPrice.multiply(quantity).setScale(2, RoundingMode.HALF_UP);

            String concept = productName != null && !productName.isBlank() ? productName : "Workspace booking";
            String description = concept;
            if (fechaIni != null) {
                description += " · " + DAY_FORMAT.format(fechaIni.toLocalDate());
            }

            BigDecimal vatAmount = lineTotal.multiply(BigDecimal.valueOf(21))
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
            BigDecimal total = lineTotal.add(vatAmount);

            Long nextId = jdbcTemplate.queryForObject("SELECT COALESCE(MAX(id), 0) + 1 FROM beworking.facturas", Long.class);
            Integer nextLegacy = jdbcTemplate.queryForObject("SELECT COALESCE(MAX(idfactura), 0) + 1 FROM beworking.facturas", Integer.class);
            LocalDateTime now = LocalDateTime.now();

            jdbcTemplate.update(
                """
                INSERT INTO beworking.facturas
                (id, idfactura, idcliente, idcentro, descripcion, total, iva, totaliva, estado, creacionfecha)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                nextId, nextLegacy, contactId, centerId, description,
                lineTotal, 21, total, "Pagado", Timestamp.valueOf(now)
            );

            Long nextDesgloseId = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(id), 0) + 1 FROM beworking.facturasdesglose", Long.class);
            jdbcTemplate.update(
                """
                INSERT INTO beworking.facturasdesglose
                (id, idfacturadesglose, conceptodesglose, precioundesglose, cantidaddesglose, totaldesglose, desgloseconfirmado, idbloqueovinculado)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                nextDesgloseId, nextLegacy, concept, unitPrice, quantity, lineTotal, 1, bloqueoId
            );

            Map<String, Object> entry = new HashMap<>();
            entry.put("facturaId", nextId);
            entry.put("idFactura", nextLegacy);
            entry.put("bloqueoId", bloqueoId);
            entry.put("clientName", clientName);
            entry.put("product", concept);
            entry.put("subtotal", lineTotal);
            entry.put("total", total);
            entry.put("estado", "Pagado");
            entry.put("rateSource", rateSource);
            entry.put("unitPrice", unitPrice);
            entry.put("hours", quantity);
            created.add(entry);
        }

        return created;
    }

    /**
     * Look up the contact's most recent tarifa for a given product from properly-invoiced bloqueos.
     * Falls back to the product average if no contact-specific rate exists.
     */
    private Double lookupHistoricalRate(Long contactId, Long productId) {
        // 1) Most recent rate for this contact + product
        try {
            Double rate = jdbcTemplate.queryForObject(
                """
                SELECT b.tarifa FROM beworking.bloqueos b
                JOIN beworking.facturasdesglose fd ON fd.idbloqueovinculado = b.id
                WHERE b.id_cliente = ? AND b.id_producto = ? AND b.tarifa IS NOT NULL AND b.tarifa > 0
                ORDER BY b.fecha_ini DESC LIMIT 1
                """, Double.class, contactId, productId);
            if (rate != null) return rate;
        } catch (EmptyResultDataAccessException ignored) {}

        // 2) Product average across all contacts
        try {
            Double avg = jdbcTemplate.queryForObject(
                """
                SELECT AVG(b.tarifa) FROM beworking.bloqueos b
                JOIN beworking.facturasdesglose fd ON fd.idbloqueovinculado = b.id
                WHERE b.id_producto = ? AND b.tarifa IS NOT NULL AND b.tarifa > 0
                """, Double.class, productId);
            if (avg != null) return avg;
        } catch (EmptyResultDataAccessException ignored) {}

        return null;
    }

    private record LineComputation(String concept, BigDecimal quantity, BigDecimal unitPrice, BigDecimal total) { }

    public String getNextInvoiceNumber() {
        // Default to Partners cuenta (ID 3) for backward compatibility
        return cuentaService.generateNextInvoiceNumber(3);
    }
    
    public String getNextInvoiceNumber(Integer cuentaId) {
        return cuentaService.generateNextInvoiceNumber(cuentaId);
    }
    
    public String getNextInvoiceNumber(String cuentaCodigo) {
        return cuentaService.generateNextInvoiceNumber(cuentaCodigo);
    }

    @Transactional
    public Map<String, Object> createManualInvoice(CreateManualInvoiceRequest request) {
        try {
            // Get the cuenta ID from the codigo
            Integer cuentaId = null;
            if (request.getCuenta() != null && !request.getCuenta().isEmpty()) {
                Optional<com.beworking.cuentas.Cuenta> cuentaOpt = cuentaService.getCuentaByCodigo(request.getCuenta());
                if (cuentaOpt.isPresent()) {
                    cuentaId = cuentaOpt.get().getId();
                }
            }

            // Generate invoice number if not provided
            String invoiceNumber = request.getInvoiceNum();
            if (invoiceNumber == null || invoiceNumber.isEmpty()) {
                if (cuentaId != null) {
                    invoiceNumber = cuentaService.generateNextInvoiceNumber(cuentaId);
                } else {
                    invoiceNumber = getNextInvoiceNumber(); // Default to Partners
                }
            }

            // Generate internal primary key for facturas.id
            Long nextInternalId = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(id), 0) + 1 FROM beworking.facturas",
                Long.class
            );

            // Build description from line items
            String description = null;
            if (request.getLineItems() != null && !request.getLineItems().isEmpty()) {
                description = request.getLineItems().stream()
                    .map(li -> li.getDescription() != null ? li.getDescription() : "Item")
                    .collect(java.util.stream.Collectors.joining(", "));
            }

            // Resolve center ID from string
            Integer centerId = null;
            if (request.getCenter() != null && !request.getCenter().isEmpty()) {
                try {
                    centerId = Integer.parseInt(request.getCenter());
                } catch (NumberFormatException ignored) {}
            }

            // Insert the invoice into the database
            String insertSql = """
                INSERT INTO beworking.facturas (
                    id, idfactura, idcliente, idcentro, holdedcuenta, id_cuenta,
                    descripcion, holdedinvoicenum,
                    fechacreacionreal, fechacobro1, estado,
                    total, iva, totaliva, notas, creacionfecha, stripeinvoiceid
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, ?)
                RETURNING idfactura
                """;

            // Convert invoice number to integer (remove prefix)
            Integer invoiceId;
            if (invoiceNumber != null && !invoiceNumber.isEmpty()) {
                String numericPart = invoiceNumber.replaceAll("[^0-9]", "");
                if (!numericPart.isEmpty()) {
                    invoiceId = Integer.parseInt(numericPart);
                } else {
                    // Fallback: generate a simple sequential number
                    invoiceId = jdbcTemplate.queryForObject("SELECT COALESCE(MAX(idfactura), 0) + 1 FROM beworking.facturas", Integer.class);
                }
            } else {
                // Fallback: generate a simple sequential number
                invoiceId = jdbcTemplate.queryForObject("SELECT COALESCE(MAX(idfactura), 0) + 1 FROM beworking.facturas", Integer.class);
            }

            String normalizedStatus = normalizeInvoiceStatus(request.getStatus());

            // Derive VAT percentage from the first line item (all lines share the same rate)
            int vatPercent = 21; // default
            if (request.getLineItems() != null && !request.getLineItems().isEmpty()) {
                vatPercent = request.getLineItems().get(0).getVatPercent().intValue();
            }

            Integer facturaId = jdbcTemplate.queryForObject(insertSql, Integer.class,
                nextInternalId,
                invoiceId,
                request.getClientId(),
                centerId,
                request.getCuenta(),
                cuentaId,
                description,
                invoiceNumber,
                request.getDate(),
                request.getDueDate(),
                normalizedStatus,
                request.getComputed().getTotal(),
                vatPercent,
                request.getComputed().getTotalVat(),
                request.getNote(),
                request.getStripeInvoiceId()
            );

            // Insert line items
            if (request.getLineItems() != null && !request.getLineItems().isEmpty()) {
                Long nextDesgloseId = jdbcTemplate.queryForObject(
                    "SELECT COALESCE(MAX(id), 0) + 1 FROM beworking.facturasdesglose",
                    Long.class
                );

                String lineItemSql = """
                    INSERT INTO beworking.facturasdesglose (
                        id, idfacturadesglose, conceptodesglose, precioundesglose,
                        cantidaddesglose, totaldesglose, desgloseconfirmado, idbloqueovinculado
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """;

                for (int i = 0; i < request.getLineItems().size(); i++) {
                    CreateManualInvoiceRequest.LineItem item = request.getLineItems().get(i);
                    BigDecimal unitPrice = item.getPrice().setScale(2, RoundingMode.HALF_UP);
                    BigDecimal lineTotal = unitPrice.multiply(item.getQuantity())
                        .setScale(2, RoundingMode.HALF_UP);

                    String lineConcept = item.getDescription();
                    if (lineConcept == null || lineConcept.isBlank()) {
                        lineConcept = "Manual line item";
                    }

                    jdbcTemplate.update(lineItemSql,
                        nextDesgloseId + i,
                        facturaId,
                        lineConcept,
                        unitPrice,
                        item.getQuantity(),
                        lineTotal,
                        1,
                        null
                    );
                }
            }

            Map<String, Object> response = new HashMap<>();
            response.put("id", nextInternalId);
            response.put("idFactura", facturaId);
            response.put("invoiceNumber", invoiceNumber);
            response.put("message", "Manual invoice created successfully");
            response.put("status", normalizedStatus);

            return response;

        } catch (Exception e) {
            throw new RuntimeException("Failed to create manual invoice: " + e.getMessage(), e);
        }
    }
}
