package com.beworking.invoices;

import com.beworking.bookings.Bloqueo;
import com.beworking.bookings.BloqueoRepository;
import com.beworking.cuentas.CuentaService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.sql.Timestamp;
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
import org.springframework.web.client.RestClient;

@Service
public class InvoiceService {

    private static final int MAX_PAGE_SIZE = 200;
    private static final DateTimeFormatter DAY_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE;

    private final JdbcTemplate jdbcTemplate;
    private final RestClient http;
    private final BloqueoRepository bloqueoRepository;
    private final CuentaService cuentaService;

    public InvoiceService(JdbcTemplate jdbcTemplate, BloqueoRepository bloqueoRepository, CuentaService cuentaService) {
        this.jdbcTemplate = jdbcTemplate;
        this.http = RestClient.create();
        this.bloqueoRepository = bloqueoRepository;
        this.cuentaService = cuentaService;
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
            where.append("""
                AND (
                    LOWER(COALESCE(c.name, '')) LIKE ?
                    OR LOWER(COALESCE(c.contact_name, '')) LIKE ?
                    OR LOWER(COALESCE(c.billing_name, '')) LIKE ?
                )
                """);
            args.add(like);
            args.add(like);
            args.add(like);
        }

        if (hasText(filters.email())) {
            String like = "%" + filters.email().trim().toLowerCase() + "%";
            where.append("""
                AND (
                    LOWER(COALESCE(c.email_primary, '')) LIKE ?
                    OR LOWER(COALESCE(c.email_secondary, '')) LIKE ?
                    OR LOWER(COALESCE(c.email_tertiary, '')) LIKE ?
                    OR LOWER(COALESCE(c.representative_email, '')) LIKE ?
                )
                """);
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
            where.append("""
                AND (
                    LOWER(COALESCE(p.nombre, '')) LIKE ?
                    OR LOWER(COALESCE(fd.conceptodesglose, '')) LIKE ?
                )
                """);
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
                MAX(c.name) AS client_name,
                MAX(
                    COALESCE(
                        c.email_primary,
                        c.email_secondary,
                        c.email_tertiary,
                        c.representative_email
                    )
                ) AS client_email,
                MAX(c.tenant_type) AS tenant_type,
                LEFT(STRING_AGG(DISTINCT p.nombre, ', ' ORDER BY p.nombre), 500) AS products
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
            where.append("""
                AND (
                    LOWER(COALESCE(c.name, '')) LIKE ?
                    OR LOWER(COALESCE(c.contact_name, '')) LIKE ?
                    OR LOWER(COALESCE(c.billing_name, '')) LIKE ?
                )
                """);
            args.add(like);
            args.add(like);
            args.add(like);
        }

        if (hasText(filters.email())) {
            String like = "%" + filters.email().trim().toLowerCase() + "%";
            where.append("""
                AND (
                    LOWER(COALESCE(c.email_primary, '')) LIKE ?
                    OR LOWER(COALESCE(c.email_secondary, '')) LIKE ?
                    OR LOWER(COALESCE(c.email_tertiary, '')) LIKE ?
                    OR LOWER(COALESCE(c.representative_email, '')) LIKE ?
                )
                """);
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
            where.append("""
                AND (
                    LOWER(COALESCE(p.nombre, '')) LIKE ?
                    OR LOWER(COALESCE(fd.conceptodesglose, '')) LIKE ?
                )
                """);
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
        Double tarifa = bloqueo.getTarifa();
        BigDecimal unitPrice = tarifa != null
            ? BigDecimal.valueOf(tarifa).setScale(2, RoundingMode.HALF_UP)
            : BigDecimal.ZERO;
        BigDecimal lineTotal = unitPrice.multiply(quantity).setScale(2, RoundingMode.HALF_UP);

        return new LineComputation(concept, quantity, unitPrice, lineTotal);
    }

    private static String buildConcept(String productName, String centerName, Bloqueo bloqueo) {
        List<String> parts = new ArrayList<>();
        if (productName != null && !productName.isBlank()) {
            parts.add(productName);
        } else {
            parts.add("Workspace booking");
        }
        if (centerName != null && !centerName.isBlank()) {
            parts.add(centerName);
        }

        LocalDateTime start = bloqueo.getFechaIni();
        LocalDateTime end = bloqueo.getFechaFin();
        if (start != null && end != null) {
            parts.add(formatRange(start, end));
        } else if (start != null) {
            parts.add(DAY_FORMAT.format(start.toLocalDate()));
        }
        return String.join(" · ", parts);
    }

    private static String formatRange(LocalDateTime start, LocalDateTime end) {
        LocalDate startDate = start.toLocalDate();
        LocalDate endDate = end.toLocalDate();
        if (startDate.equals(endDate)) {
            return DAY_FORMAT.format(startDate) + " " + start.toLocalTime() + "-" + end.toLocalTime();
        }
        return DAY_FORMAT.format(startDate) + " - " + DAY_FORMAT.format(endDate);
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

            // Insert the invoice into the database
            String insertSql = """
                INSERT INTO beworking.facturas (
                    id, idfactura, idcliente, holdedcuenta, id_cuenta, 
                    fechacreacionreal, fechacobro1, estado, 
                    total, iva, totaliva, notas, creacionfecha
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
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

            // Debugging: Print the resolved invoiceId
            System.out.println("Resolved invoiceId before insert: " + invoiceId);

            String normalizedStatus = normalizeInvoiceStatus(request.getStatus());

            Integer facturaId = jdbcTemplate.queryForObject(insertSql, Integer.class,
                nextInternalId,
                invoiceId,
                request.getClientId(),
                request.getCuenta(),
                cuentaId,
                request.getDate(),
                request.getDueDate(),
                normalizedStatus,
                request.getComputed().getTotal(),
                request.getComputed().getTotalVat().intValue(), // Convert BigDecimal to Integer for iva column
                request.getComputed().getTotalVat(),
                request.getNote()
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
                        .multiply(BigDecimal.ONE.add(item.getVatPercent().divide(BigDecimal.valueOf(100))))
                        .setScale(2, RoundingMode.HALF_UP);

                    String description = item.getDescription();
                    if (description == null || description.isBlank()) {
                        description = "Manual line item";
                    }

                    jdbcTemplate.update(lineItemSql,
                        nextDesgloseId + i,
                        facturaId,
                        description,
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
