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

        Color primary = new Color(30, 64, 175);
        Color background = new Color(248, 250, 252);
        Color border = new Color(226, 232, 240);
        Color bodyText = new Color(30, 41, 59);
        Color mutedText = new Color(100, 116, 139);

        try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
            float headerHeight = 90f;
            float headerTop = height - margin;
            float headerBottom = headerTop - headerHeight;

            cs.setNonStrokingColor(primary);
            cs.addRect(margin, headerBottom, contentWidth, headerHeight);
            cs.fill();

            cs.setNonStrokingColor(Color.WHITE);
            addText(cs, PDType1Font.HELVETICA_BOLD, 24, margin + 24, headerTop - 32, "BeWorking");
            addText(cs, PDType1Font.HELVETICA, 12, margin + 24, headerTop - 54, "Invoice #" + header.displayNumber());
            String dateLabel = header.issuedAt() != null
                ? DATE_FORMAT.format(header.issuedAt().atZone(ZoneId.systemDefault()))
                : "—";
            addText(cs, PDType1Font.HELVETICA, 11, margin + contentWidth - 180, headerTop - 32, "Issued on");
            addText(cs, PDType1Font.HELVETICA_BOLD, 12, margin + contentWidth - 180, headerTop - 48, dateLabel);

            float cursorY = headerBottom - 32;

            cs.setStrokingColor(border);
            cs.setLineWidth(0.6f);
            cs.moveTo(margin, cursorY);
            cs.lineTo(margin + contentWidth, cursorY);
            cs.stroke();
            cursorY -= 24;

            float columnGap = 32f;
            float columnWidth = (contentWidth - columnGap) / 2f;
            float leftX = margin;
            float rightX = margin + columnWidth + columnGap;

            String centerLabel = centerName != null
                ? centerName + (header.centerId() != null ? " · #" + header.centerId() : "")
                : (header.centerId() != null ? "#" + header.centerId() : "—");

            float leftY = cursorY;
            leftY = drawLabelValue(cs, leftX, leftY, "Invoice number", header.displayNumber(), bodyText, mutedText);
            leftY = drawLabelValue(cs, leftX, leftY, "Center", centerLabel, bodyText, mutedText);
            leftY = drawLabelValue(cs, leftX, leftY, "Client ID", header.clientId() != null ? header.clientId().toString() : "—", bodyText, mutedText);

            String clientBlock = buildClientBlock(clientInfo);
            float rightY = cursorY;
            rightY = drawLabelValue(cs, rightX, rightY, "Bill to", clientBlock, bodyText, mutedText);

            cursorY = Math.min(leftY, rightY) - 18;

            if (header.description() != null && !header.description().isBlank()) {
                List<String> descriptionLines = wrapText(header.description(), PDType1Font.HELVETICA, 11, contentWidth - 32);
                float boxHeight = descriptionLines.size() * 14f + 24f;
                cs.setNonStrokingColor(background);
                cs.addRect(margin, cursorY - boxHeight, contentWidth, boxHeight);
                cs.fill();

                float textY = cursorY - 18;
                cs.setNonStrokingColor(bodyText);
                for (String line : descriptionLines) {
                    addText(cs, PDType1Font.HELVETICA, 11, margin + 16, textY, line);
                    textY -= 14;
                }
                cursorY = cursorY - boxHeight - 24;
            }

            float tableTop = cursorY;
            float[] colWidths = new float[]{
                contentWidth * 0.5f,
                contentWidth * 0.13f,
                contentWidth * 0.17f,
                contentWidth * 0.20f
            };
            float tableWidth = sum(colWidths);

            float headerRowHeight = 24f;
            cs.setNonStrokingColor(primary);
            cs.addRect(margin, tableTop - headerRowHeight, tableWidth, headerRowHeight);
            cs.fill();

            cs.setNonStrokingColor(Color.WHITE);
            String[] headers = {"Description", "Qty", "Unit price", "Line total"};
            float textX = margin + 12;
            for (int i = 0; i < headers.length; i++) {
                addText(cs, PDType1Font.HELVETICA_BOLD, 11, textX, tableTop - headerRowHeight + 14, headers[i].toUpperCase());
                textX += colWidths[i];
            }

            float currentY = tableTop - headerRowHeight;
            boolean shade = false;
            List<LineItem> rows = lines.isEmpty() ? List.of(new LineItem("—", null, null, null)) : lines;
            for (LineItem line : rows) {
                List<String> descLines = wrapText(line.concept(), PDType1Font.HELVETICA, 11, colWidths[0] - 20);
                float bodyHeight = Math.max(26f, descLines.size() * 14f + 10f);

                if (shade) {
                    cs.setNonStrokingColor(background);
                    cs.addRect(margin, currentY - bodyHeight, tableWidth, bodyHeight);
                    cs.fill();
                }
                shade = !shade;

                float cellY = currentY - 14;
                cs.setNonStrokingColor(bodyText);
                float cellX = margin + 12;
                for (String desc : descLines) {
                    addText(cs, PDType1Font.HELVETICA, 11, cellX, cellY, desc);
                    cellY -= 14;
                }

                String quantityText = line.quantity() != null ? numberFormat.format(line.quantity()) : "—";
                String unitText = line.unitPrice() != null ? currencyFormat.format(line.unitPrice()) : "—";
                String totalText = line.total() != null ? currencyFormat.format(line.total()) : "—";

                float qtyX = margin + colWidths[0] + 12;
                addText(cs, PDType1Font.HELVETICA, 11, qtyX, currentY - 14, quantityText);

                float unitX = qtyX + colWidths[1];
                addText(cs, PDType1Font.HELVETICA, 11, unitX, currentY - 14, unitText);

                float totalX = unitX + colWidths[2];
                addText(cs, PDType1Font.HELVETICA_BOLD, 11, totalX, currentY - 14, totalText);

                cs.setStrokingColor(border);
                cs.moveTo(margin, currentY - bodyHeight);
                cs.lineTo(margin + tableWidth, currentY - bodyHeight);
                cs.stroke();

                currentY -= bodyHeight;
            }

            cursorY = currentY - 32;

            float summaryWidth = contentWidth * 0.35f;
            float summaryX = margin + contentWidth - summaryWidth;

            cs.setNonStrokingColor(background);
            cs.addRect(summaryX, cursorY - 90, summaryWidth, 90);
            cs.fill();

            float summaryY = cursorY - 20;
            cs.setNonStrokingColor(mutedText);
            addText(cs, PDType1Font.HELVETICA, 10, summaryX + 16, summaryY, "Subtotal");
            cs.setNonStrokingColor(bodyText);
            addTextRightAligned(cs, PDType1Font.HELVETICA_BOLD, 12, summaryX + summaryWidth - 16, summaryY, formatCurrency(subtotal, currencyFormat));

            summaryY -= 18;
            String vatLabel = header.vatPercent() != null
                ? "VAT (" + header.vatPercent().stripTrailingZeros().toPlainString() + "%)"
                : "VAT";
            cs.setNonStrokingColor(mutedText);
            addText(cs, PDType1Font.HELVETICA, 10, summaryX + 16, summaryY, vatLabel);
            cs.setNonStrokingColor(bodyText);
            addTextRightAligned(cs, PDType1Font.HELVETICA_BOLD, 12, summaryX + summaryWidth - 16, summaryY, formatCurrency(taxAmount, currencyFormat));

            summaryY -= 22;
            cs.setNonStrokingColor(primary);
            addText(cs, PDType1Font.HELVETICA_BOLD, 12, summaryX + 16, summaryY, "Total due");
            addTextRightAligned(cs, PDType1Font.HELVETICA_BOLD, 14, summaryX + summaryWidth - 16, summaryY, formatCurrency(total, currencyFormat));

            cs.setStrokingColor(border);
            cs.moveTo(margin, summaryY - 28);
            cs.lineTo(margin + contentWidth, summaryY - 28);
            cs.stroke();

            cs.setNonStrokingColor(mutedText);
            addText(cs, PDType1Font.HELVETICA, 10, margin, summaryY - 44, "Thank you for being part of the BeWorking community!");
        }
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

    private static float drawLabelValue(PDPageContentStream cs,
                                        float x,
                                        float startY,
                                        String label,
                                        String value,
                                        Color valueColor,
                                        Color labelColor) throws IOException {
        cs.setNonStrokingColor(labelColor);
        addText(cs, PDType1Font.HELVETICA, 8, x, startY, label.toUpperCase());
        float y = startY - 12;
        cs.setNonStrokingColor(valueColor);
        String safe = (value == null || value.isBlank()) ? "—" : value;
        for (String line : safe.split("\\r?\\n")) {
            addText(cs, PDType1Font.HELVETICA_BOLD, 12, x, y, line);
            y -= 14;
        }
        return y - 6;
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
