package com.beworking.invoices;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.text.NumberFormat;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/invoices")
public class InvoicePdfController {

    private static final Locale LOCALE_ES = new Locale("es", "ES");
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("d MMM yyyy", LOCALE_ES);

    private final JdbcTemplate jdbcTemplate;

    public InvoicePdfController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @GetMapping("/{id}/pdf")
    public ResponseEntity<byte[]> renderPdf(@PathVariable("id") Long id) throws IOException {
        InvoiceHeader header = fetchHeader(id);
        if (header == null) {
            return ResponseEntity.notFound().build();
        }

        List<LineItem> lines = fetchLines(id);
        ClientInfo clientInfo = fetchClientInfo(header.clientId());
        String centerName = fetchCenterName(header.centerId());

        BigDecimal subtotal = lines.stream()
            .map(LineItem::total)
            .filter(Objects::nonNull)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (subtotal != null && subtotal.compareTo(BigDecimal.ZERO) == 0) {
            subtotal = null;
        }

        BigDecimal total = header.total() != null ? header.total() : subtotal;
        BigDecimal taxAmount = null;
        if (total != null && subtotal != null) {
            taxAmount = total.subtract(subtotal);
        }

        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);

            drawInvoice(doc, page, header, clientInfo, centerName, lines, subtotal, taxAmount, total);

            doc.save(out);
            String filename = "invoice-" + header.displayNumber() + ".pdf";
            return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=" + filename)
                .contentType(MediaType.APPLICATION_PDF)
                .body(out.toByteArray());
        }
    }

    private InvoiceHeader fetchHeader(Long id) {
        List<InvoiceHeader> headers = jdbcTemplate.query(
            """
            SELECT id, idfactura, idcliente, idcentro, descripcion, total, iva, creacionfecha
            FROM beworking.facturas
            WHERE id = ?
            """,
            (rs, rowNum) -> new InvoiceHeader(
                rs.getLong("id"),
                (Integer) rs.getObject("idfactura"),
                (Long) rs.getObject("idcliente"),
                (Integer) rs.getObject("idcentro"),
                rs.getString("descripcion"),
                rs.getBigDecimal("total"),
                toBigDecimal(rs.getObject("iva")),
                toDateTime(rs.getTimestamp("creacionfecha"))
            ),
            id
        );
        return headers.isEmpty() ? null : headers.get(0);
    }

    private List<LineItem> fetchLines(Long id) {
        return jdbcTemplate.query(
            """
            SELECT conceptodesglose AS concept,
                   cantidaddesglose AS quantity,
                   precioundesglose AS unit_price,
                   totaldesglose AS line_total
            FROM beworking.facturasdesglose
            WHERE idfacturadesglose = (
                SELECT idfactura FROM beworking.facturas WHERE id = ?
            )
            ORDER BY id
            """,
            (rs, rowNum) -> new LineItem(
                rs.getString("concept"),
                toBigDecimal(rs.getObject("quantity")),
                toBigDecimal(rs.getObject("unit_price")),
                toBigDecimal(rs.getObject("line_total"))
            ),
            id
        );
    }

    private ClientInfo fetchClientInfo(Long clientId) {
        if (clientId == null) {
            return null;
        }
        try {
            return jdbcTemplate.queryForObject(
                """
                SELECT name,
                       COALESCE(email_primary, email_secondary, email_tertiary, representative_email) AS email,
                       billing_address,
                       billing_postal_code,
                       billing_city,
                       billing_province,
                       billing_country,
                       billing_tax_id
                FROM beworking.contact_profiles
                WHERE id = ?
                """,
                (rs, rowNum) -> new ClientInfo(
                    rs.getString("name"),
                    rs.getString("email"),
                    rs.getString("billing_address"),
                    rs.getString("billing_postal_code"),
                    rs.getString("billing_city"),
                    rs.getString("billing_province"),
                    rs.getString("billing_country"),
                    rs.getString("billing_tax_id")
                ),
                clientId
            );
        } catch (EmptyResultDataAccessException ex) {
            return null;
        }
    }

    private String fetchCenterName(Integer centerId) {
        if (centerId == null) {
            return null;
        }
        try {
            return jdbcTemplate.queryForObject(
                "SELECT nombre FROM beworking.centros WHERE id = ?",
                String.class,
                centerId
            );
        } catch (EmptyResultDataAccessException ex) {
            return null;
        }
    }

    private void drawInvoice(PDDocument doc,
                             PDPage page,
                             InvoiceHeader header,
                             ClientInfo clientInfo,
                             String centerName,
                             List<LineItem> lines,
                             BigDecimal subtotal,
                             BigDecimal taxAmount,
                             BigDecimal total) throws IOException {

        PDRectangle mediaBox = page.getMediaBox();
        float width = mediaBox.getWidth();
        float height = mediaBox.getHeight();
        float margin = 48f;
        float contentWidth = width - 2 * margin;

        NumberFormat currencyFormat = NumberFormat.getCurrencyInstance(LOCALE_ES);
        currencyFormat.setMinimumFractionDigits(2);
        NumberFormat numberFormat = NumberFormat.getNumberInstance(LOCALE_ES);
        numberFormat.setMaximumFractionDigits(2);

        Color primary = new Color(25, 25, 25);
        Color mutedText = new Color(110, 118, 129);
        Color lightLine = new Color(210, 214, 220);

        PDPage currentPage = page;
        PDPageContentStream cs = null;
        try {
            cs = new PDPageContentStream(doc, currentPage);
            float cursorY = drawHeaderSection(cs, header, clientInfo, centerName, total, currencyFormat,
                contentWidth, height, margin, primary, mutedText, lightLine, true);

            float[] colWidths = new float[]{
                contentWidth * 0.4f,
                contentWidth * 0.1f,
                contentWidth * 0.2f,
                contentWidth * 0.3f
            };
            float headerRowHeight = 24f;
            float tableWidth = sum(colWidths);

            float currentY = drawTableHeader(cs, margin, colWidths, headerRowHeight, cursorY, primary);

            List<LineItem> rows = lines.isEmpty() ? List.of(new LineItem("—", null, null, null)) : lines;
            float summaryReserve = 140f;
            for (int i = 0; i < rows.size(); i++) {
                LineItem line = rows.get(i);
                List<String> descLines = wrapText(line.concept(), PDType1Font.HELVETICA, 10, colWidths[0] - 16);
                float bodyHeight = Math.max(24f, descLines.size() * 12f + 12f);
                boolean isLastRow = (i == rows.size() - 1);
                float reserve = isLastRow ? summaryReserve : 30f;

                if (currentY - bodyHeight - reserve < margin) {
                    cs.close();
                    currentPage = new PDPage(PDRectangle.A4);
                    doc.addPage(currentPage);
                    cs = new PDPageContentStream(doc, currentPage);
                    cursorY = drawHeaderSection(cs, header, clientInfo, centerName, total, currencyFormat,
                        contentWidth, height, margin, primary, mutedText, lightLine, false);
                    currentY = drawTableHeader(cs, margin, colWidths, headerRowHeight, cursorY, primary);
                }

                float cellY = currentY - 12;
                float cellX = margin + 8;
                cs.setNonStrokingColor(primary);
                for (String desc : descLines) {
                    addText(cs, PDType1Font.HELVETICA, 10, cellX, cellY, desc);
                    cellY -= 12;
                }

                String quantityText = line.quantity() != null ? numberFormat.format(line.quantity()) : "—";
                String unitText = line.unitPrice() != null ? currencyFormat.format(line.unitPrice()) : "—";
                String lineTotalText = line.total() != null ? currencyFormat.format(line.total()) : "—";

                addText(cs, PDType1Font.HELVETICA, 10, margin + colWidths[0] + 8, currentY - 12, quantityText);
                addTextRightAligned(cs, PDType1Font.HELVETICA, 10, margin + colWidths[0] + colWidths[1] + colWidths[2] - 8, currentY - 12, unitText);
                addTextRightAligned(cs, PDType1Font.HELVETICA, 10, margin + tableWidth - 8, currentY - 12, lineTotalText);

                currentY -= bodyHeight;
            }

            if (currentY < margin + summaryReserve) {
                cs.close();
                currentPage = new PDPage(PDRectangle.A4);
                doc.addPage(currentPage);
                cs = new PDPageContentStream(doc, currentPage);
                cursorY = drawHeaderSection(cs, header, clientInfo, centerName, total, currencyFormat,
                    contentWidth, height, margin, primary, mutedText, lightLine, false);
                currentY = drawTableHeader(cs, margin, colWidths, headerRowHeight, cursorY, primary);
            }

            float summaryLabelX = margin + contentWidth * 0.6f;
            float summaryY = currentY - 12;
            cs.setNonStrokingColor(primary);
            addText(cs, PDType1Font.HELVETICA, 10, summaryLabelX, summaryY, "Base imponible");
            addTextRightAligned(cs, PDType1Font.HELVETICA, 10, margin + contentWidth, summaryY, formatCurrency(subtotal, currencyFormat));

            summaryY -= 16;
            String vatLabel = header.vatPercent() != null
                ? "IVA " + header.vatPercent().stripTrailingZeros().toPlainString() + "%"
                : "IVA";
            addText(cs, PDType1Font.HELVETICA, 10, summaryLabelX, summaryY, vatLabel);
            addTextRightAligned(cs, PDType1Font.HELVETICA, 10, margin + contentWidth, summaryY, formatCurrency(taxAmount, currencyFormat));

            summaryY -= 18;
            addText(cs, PDType1Font.HELVETICA_BOLD, 12, summaryLabelX, summaryY, "TOTAL");
            addTextRightAligned(cs, PDType1Font.HELVETICA_BOLD, 12, margin + contentWidth, summaryY, formatCurrency(total, currencyFormat));

            summaryY -= 40;
            addText(cs, PDType1Font.HELVETICA, 9, margin, summaryY, "Gracias por ser parte de la comunidad BeWorking.");
        } finally {
            if (cs != null) {
                cs.close();
            }
        }
    }

    private float drawHeaderSection(PDPageContentStream cs,
                                    InvoiceHeader header,
                                    ClientInfo clientInfo,
                                    String centerName,
                                    BigDecimal total,
                                    NumberFormat currencyFormat,
                                    float contentWidth,
                                    float height,
                                    float margin,
                                    Color primary,
                                    Color mutedText,
                                    Color lightLine,
                                    boolean includeDetails) throws IOException {
        float cursorY = height - margin;

        cs.setNonStrokingColor(primary);
        addText(cs, PDType1Font.HELVETICA_BOLD, 20, margin, cursorY, "BeWorking");

        List<String> companyLines = List.of(
            "BeWorking Partners Offices SL",
            "Calle Alejandro Dumas, 17 · Oficinas",
            "Málaga (29004), Málaga, España",
            "accounts@be-working.com",
            "NIF: B09665258"
        );
        float companyY = cursorY - 14;
        for (String line : companyLines) {
            addText(cs, PDType1Font.HELVETICA, 10, margin, companyY, line);
            companyY -= 12;
        }

        cursorY = companyY - 16;
        cs.setStrokingColor(lightLine);
        cs.setLineWidth(0.8f);
        cs.moveTo(margin, cursorY);
        cs.lineTo(margin + contentWidth, cursorY);
        cs.stroke();

        cursorY -= 24;
        String dateLabel = header.issuedAt() != null
            ? DATE_FORMAT.format(header.issuedAt().atZone(ZoneId.systemDefault()))
            : "—";
        addText(cs, PDType1Font.HELVETICA_BOLD, 13, margin, cursorY, "FACTURA #" + header.displayNumber());
        addText(cs, PDType1Font.HELVETICA, 10, margin, cursorY - 14, "Fecha: " + dateLabel);

        String totalText = formatCurrency(total, currencyFormat);
        addTextRightAligned(cs, PDType1Font.HELVETICA_BOLD, 14, margin + contentWidth, cursorY, "TOTAL " + totalText);

        cursorY -= 32;

        if (includeDetails) {
            addText(cs, PDType1Font.HELVETICA_BOLD, 10, margin, cursorY, "Cliente");
            addTextRightAligned(cs, PDType1Font.HELVETICA_BOLD, 10, margin + contentWidth, cursorY, "Detalles de la factura");
            cursorY -= 14;

            String clientBlock = buildClientBlock(clientInfo);
            String[] clientLines = clientBlock.split("\\r?\\n");
            float clientY = cursorY;
            cs.setNonStrokingColor(primary);
            for (String line : clientLines) {
                addText(cs, PDType1Font.HELVETICA, 10, margin, clientY, line);
                clientY -= 12;
            }

            String centerLabel = centerName != null ? centerName : "Centro no disponible";
            float metaY = cursorY;
            addTextRightAligned(cs, PDType1Font.HELVETICA, 10, margin + contentWidth, metaY, "Centro: " + centerLabel);
            metaY -= 12;
            String clientIdText = header.clientId() != null ? header.clientId().toString() : "—";
            addTextRightAligned(cs, PDType1Font.HELVETICA, 10, margin + contentWidth, metaY, "ID Cliente: " + clientIdText);

            cursorY = Math.min(clientY, metaY) - 18;
        } else {
            cursorY -= 18;
        }

        cs.setStrokingColor(lightLine);
        cs.setLineWidth(0.8f);
        cs.moveTo(margin, cursorY);
        cs.lineTo(margin + contentWidth, cursorY);
        cs.stroke();
        cursorY -= 12;

        return cursorY;
    }

    private float drawTableHeader(PDPageContentStream cs,
                                  float margin,
                                  float[] colWidths,
                                  float headerRowHeight,
                                  float cursorY,
                                  Color primary) throws IOException {
        float tableWidth = sum(colWidths);
        String[] headers = {"DESCRIPCIÓN", "CANT.", "PRECIO UNIT.", "IMPORTE"};

        cs.setNonStrokingColor(primary);
        addText(cs, PDType1Font.HELVETICA_BOLD, 10, margin + 8, cursorY - headerRowHeight + 12, headers[0]);
        addText(cs, PDType1Font.HELVETICA_BOLD, 10, margin + colWidths[0] + 8, cursorY - headerRowHeight + 12, headers[1]);
        addTextRightAligned(cs, PDType1Font.HELVETICA_BOLD, 10, margin + colWidths[0] + colWidths[1] + colWidths[2] - 8, cursorY - headerRowHeight + 12, headers[2]);
        addTextRightAligned(cs, PDType1Font.HELVETICA_BOLD, 10, margin + tableWidth - 8, cursorY - headerRowHeight + 12, headers[3]);

        return cursorY - headerRowHeight - 4;
    }

    private static void addTextRightAligned(PDPageContentStream cs,
                                            PDType1Font font,
                                            float fontSize,
                                            float x,
                                            float y,
                                            String text) throws IOException {
        float width = font.getStringWidth(text) / 1000f * fontSize;
        addText(cs, font, fontSize, x - width, y, text);
    }

    private static String formatCurrency(BigDecimal value, NumberFormat currencyFormat) {
        return value != null ? currencyFormat.format(value) : "—";
    }

    private static List<String> wrapText(String text, PDType1Font font, float fontSize, float maxWidth) throws IOException {
        List<String> lines = new ArrayList<>();
        if (text == null || text.isBlank()) {
            lines.add("—");
            return lines;
        }
        String[] words = text.trim().split("\\s+");
        StringBuilder current = new StringBuilder();
        for (String word : words) {
            String candidate = current.length() == 0 ? word : current + " " + word;
            float width = font.getStringWidth(candidate) / 1000f * fontSize;
            if (width > maxWidth && current.length() > 0) {
                lines.add(current.toString());
                current = new StringBuilder(word);
            } else {
                current = new StringBuilder(candidate);
            }
        }
        if (current.length() > 0) {
            lines.add(current.toString());
        }
        return lines;
    }

    private static void addText(PDPageContentStream cs, PDType1Font font, float fontSize, float x, float y, String text) throws IOException {
        cs.beginText();
        cs.setFont(font, fontSize);
        cs.newLineAtOffset(x, y);
        cs.showText(text != null ? text : "");
        cs.endText();
    }

    private static BigDecimal toBigDecimal(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof BigDecimal bd) {
            return bd;
        }
        if (value instanceof Number number) {
            return BigDecimal.valueOf(number.doubleValue());
        }
        if (value instanceof String str && !str.isBlank()) {
            try {
                return new BigDecimal(str.trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return null;
    }

    private static LocalDateTime toDateTime(Timestamp timestamp) {
        return timestamp != null ? timestamp.toLocalDateTime() : null;
    }

    private static String buildClientBlock(ClientInfo info) {
        if (info == null) {
            return "—";
        }
        List<String> parts = new ArrayList<>();
        if (info.name() != null && !info.name().isBlank()) {
            parts.add(info.name());
        }
        if (info.email() != null && !info.email().isBlank()) {
            parts.add(info.email());
        }
        List<String> addressPieces = new ArrayList<>();
        if (info.address() != null && !info.address().isBlank()) {
            addressPieces.add(info.address());
        }
        List<String> locality = new ArrayList<>();
        if (info.postalCode() != null && !info.postalCode().isBlank()) {
            locality.add(info.postalCode());
        }
        if (info.city() != null && !info.city().isBlank()) {
            locality.add(info.city());
        }
        if (info.province() != null && !info.province().isBlank()) {
            locality.add(info.province());
        }
        if (!locality.isEmpty()) {
            addressPieces.add(String.join(" · ", locality));
        }
        if (info.country() != null && !info.country().isBlank()) {
            addressPieces.add(info.country());
        }
        if (!addressPieces.isEmpty()) {
            parts.add(String.join("\n", addressPieces));
        }
        if (info.taxId() != null && !info.taxId().isBlank()) {
            parts.add("VAT ID: " + info.taxId());
        }
        return parts.isEmpty() ? "—" : String.join("\n", parts);
    }

    private static float sum(float[] values) {
        float total = 0;
        for (float value : values) {
            total += value;
        }
        return total;
    }

    private record InvoiceHeader(Long id,
                                 Integer legacyNumber,
                                 Long clientId,
                                 Integer centerId,
                                 String description,
                                 BigDecimal total,
                                 BigDecimal vatPercent,
                                 LocalDateTime issuedAt) {
        String displayNumber() {
            return legacyNumber != null ? legacyNumber.toString() : id.toString();
        }
    }

    private record LineItem(String concept, BigDecimal quantity, BigDecimal unitPrice, BigDecimal total) { }

    private record ClientInfo(String name,
                              String email,
                              String address,
                              String postalCode,
                              String city,
                              String province,
                              String country,
                              String taxId) { }
}
