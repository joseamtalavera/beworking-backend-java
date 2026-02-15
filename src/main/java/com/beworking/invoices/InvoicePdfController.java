package com.beworking.invoices;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.text.NumberFormat;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
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

    // Brand colors
    private static final Color BRAND_GREEN = new Color(0, 150, 36);       // #009624
    private static final Color BRAND_GREEN_DARK = new Color(0, 122, 29);  // #007a1d
    private static final Color BRAND_GREEN_LIGHT = new Color(46, 204, 113); // #2ecc71
    private static final Color INK = new Color(26, 26, 26);               // #1a1a1a
    private static final Color MUTED = new Color(113, 113, 122);          // #71717a
    private static final Color BORDER = new Color(229, 229, 229);         // #e5e5e5
    private static final Color BG_LIGHT = new Color(250, 250, 250);       // #fafafa
    private static final Color TABLE_HEADER_BG = new Color(245, 245, 245); // #f5f5f5
    private static final Color WHITE = Color.WHITE;

    // ─── Company info per cuenta ─────────────────────────────────────────
    private record CompanyInfo(String name, String address, String cityLine, String email, String nif) { }

    private static final CompanyInfo COMPANY_PT = new CompanyInfo(
        "BeWorking Partners Offices SL",
        "Calle Alejandro Dumas, 17 \u00b7 Oficinas",
        "M\u00e1laga (29004), M\u00e1laga, Espa\u00f1a",
        "accounts@be-working.com",
        "NIF: B09665258"
    );
    private static final CompanyInfo COMPANY_GT = new CompanyInfo(
        "GLOBALTECHNO O\u00dc",
        "Sepapaja tn 6",
        "Tallinn (15551), Estonia",
        "info@globaltechno.io",
        "VAT: EE102278691"
    );
    private static final Map<String, CompanyInfo> COMPANY_MAP = Map.of(
        "PT", COMPANY_PT,
        "OF", COMPANY_PT,
        "GT", COMPANY_GT
    );

    private static CompanyInfo resolveCompany(String cuentaCodigo) {
        if (cuentaCodigo == null) return COMPANY_PT;
        String key = cuentaCodigo.trim().toUpperCase();
        CompanyInfo info = COMPANY_MAP.get(key);
        if (info != null) return info;
        // Legacy holdedcuenta fallback (text values like "Globaltechno", "Partners", "Offices")
        if (key.contains("GLOBAL")) return COMPANY_GT;
        if (key.contains("PARTNER") || key.contains("OFFICE")) return COMPANY_PT;
        return COMPANY_PT;
    }

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
            SELECT f.id, f.idfactura, f.idcliente, f.idcentro, f.descripcion, f.total, f.iva, f.creacionfecha,
                   COALESCE(c.codigo, f.holdedcuenta) AS cuenta_codigo
            FROM beworking.facturas f
            LEFT JOIN beworking.cuentas c ON c.id = f.id_cuenta
            WHERE f.id = ?
            """,
            (rs, rowNum) -> new InvoiceHeader(
                rs.getLong("id"),
                (Integer) rs.getObject("idfactura"),
                (Long) rs.getObject("idcliente"),
                (Integer) rs.getObject("idcentro"),
                rs.getString("descripcion"),
                rs.getBigDecimal("total"),
                toBigDecimal(rs.getObject("iva")),
                toDateTime(rs.getTimestamp("creacionfecha")),
                rs.getString("cuenta_codigo")
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

    // ─── Drawing ────────────────────────────────────────────────────────

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

        CompanyInfo company = resolveCompany(header.cuentaCodigo());

        PDPage currentPage = page;
        PDPageContentStream cs = null;
        try {
            cs = new PDPageContentStream(doc, currentPage);

            // Green accent bar at top of page
            drawTopBar(cs, width);

            float cursorY = drawHeaderSection(cs, doc, header, clientInfo, centerName, total, currencyFormat,
                contentWidth, height, margin, true, company);

            // Table
            float[] colWidths = new float[]{
                contentWidth * 0.42f,
                contentWidth * 0.12f,
                contentWidth * 0.20f,
                contentWidth * 0.26f
            };
            float headerRowHeight = 28f;
            float tableWidth = sum(colWidths);

            float currentY = drawTableHeader(cs, margin, colWidths, headerRowHeight, cursorY);

            List<LineItem> rows = lines.isEmpty() ? List.of(new LineItem("\u2014", null, null, null)) : lines;
            float summaryReserve = 160f;

            for (int i = 0; i < rows.size(); i++) {
                LineItem line = rows.get(i);
                List<String> descLines = wrapText(line.concept(), PDType1Font.HELVETICA, 9.5f, colWidths[0] - 20);
                float bodyHeight = Math.max(28f, descLines.size() * 13f + 15f);
                boolean isLastRow = (i == rows.size() - 1);
                float reserve = isLastRow ? summaryReserve : 30f;

                if (currentY - bodyHeight - reserve < margin) {
                    cs.close();
                    currentPage = new PDPage(PDRectangle.A4);
                    doc.addPage(currentPage);
                    cs = new PDPageContentStream(doc, currentPage);
                    drawTopBar(cs, width);
                    cursorY = drawHeaderSection(cs, doc, header, clientInfo, centerName, total, currencyFormat,
                        contentWidth, height, margin, false, company);
                    currentY = drawTableHeader(cs, margin, colWidths, headerRowHeight, cursorY);
                }

                // Alternating row background
                if (i % 2 == 0) {
                    fillRect(cs, margin, currentY - bodyHeight, contentWidth, bodyHeight, BG_LIGHT);
                }

                // Row content
                float cellY = currentY - 14;
                float cellX = margin + 10;
                cs.setNonStrokingColor(INK);
                for (String desc : descLines) {
                    addText(cs, PDType1Font.HELVETICA, 9.5f, cellX, cellY, desc);
                    cellY -= 13;
                }

                String quantityText = line.quantity() != null ? numberFormat.format(line.quantity()) : "\u2014";
                String unitText = line.unitPrice() != null ? currencyFormat.format(line.unitPrice()) : "\u2014";
                String lineTotalText = line.total() != null ? currencyFormat.format(line.total()) : "\u2014";

                cs.setNonStrokingColor(INK);
                addText(cs, PDType1Font.HELVETICA, 9.5f, margin + colWidths[0] + 10, currentY - 14, quantityText);
                addTextRightAligned(cs, PDType1Font.HELVETICA, 9.5f, margin + colWidths[0] + colWidths[1] + colWidths[2] - 10, currentY - 14, unitText);
                addTextRightAligned(cs, PDType1Font.HELVETICA_BOLD, 9.5f, margin + tableWidth - 10, currentY - 14, lineTotalText);

                // Row separator line
                cs.setStrokingColor(BORDER);
                cs.setLineWidth(0.4f);
                cs.moveTo(margin, currentY - bodyHeight);
                cs.lineTo(margin + contentWidth, currentY - bodyHeight);
                cs.stroke();

                currentY -= bodyHeight;
            }

            // Ensure space for summary
            if (currentY < margin + summaryReserve) {
                cs.close();
                currentPage = new PDPage(PDRectangle.A4);
                doc.addPage(currentPage);
                cs = new PDPageContentStream(doc, currentPage);
                drawTopBar(cs, width);
                cursorY = drawHeaderSection(cs, doc, header, clientInfo, centerName, total, currencyFormat,
                    contentWidth, height, margin, false, company);
                currentY = drawTableHeader(cs, margin, colWidths, headerRowHeight, cursorY);
            }

            // ─── Summary section ─────────────────────────────────────
            float summaryWidth = contentWidth * 0.38f;
            float summaryX = margin + contentWidth - summaryWidth;
            float summaryY = currentY - 20;

            // Subtotal row
            cs.setNonStrokingColor(MUTED);
            addText(cs, PDType1Font.HELVETICA, 10, summaryX, summaryY, "Base imponible");
            cs.setNonStrokingColor(INK);
            addTextRightAligned(cs, PDType1Font.HELVETICA, 10, margin + contentWidth, summaryY, formatCurrency(subtotal, currencyFormat));

            // VAT row
            summaryY -= 20;
            String vatLabel = header.vatPercent() != null
                ? "IVA " + header.vatPercent().stripTrailingZeros().toPlainString() + "%"
                : "IVA";
            cs.setNonStrokingColor(MUTED);
            addText(cs, PDType1Font.HELVETICA, 10, summaryX, summaryY, vatLabel);
            cs.setNonStrokingColor(INK);
            addTextRightAligned(cs, PDType1Font.HELVETICA, 10, margin + contentWidth, summaryY, formatCurrency(taxAmount, currencyFormat));

            // Divider before total
            summaryY -= 12;
            cs.setStrokingColor(BRAND_GREEN);
            cs.setLineWidth(1.2f);
            cs.moveTo(summaryX, summaryY);
            cs.lineTo(margin + contentWidth, summaryY);
            cs.stroke();

            // Total row with green background pill
            summaryY -= 6;
            float totalBoxH = 28f;
            fillRoundedRect(cs, summaryX - 4, summaryY - totalBoxH + 6, summaryWidth + 4, totalBoxH, 0f, BRAND_GREEN);

            cs.setNonStrokingColor(WHITE);
            addText(cs, PDType1Font.HELVETICA_BOLD, 11, summaryX + 8, summaryY - 12, "TOTAL");
            addTextRightAligned(cs, PDType1Font.HELVETICA_BOLD, 12, margin + contentWidth - 8, summaryY - 12, formatCurrency(total, currencyFormat));

            // ─── Footer ──────────────────────────────────────────────
            float footerY = margin + 16;
            cs.setStrokingColor(BORDER);
            cs.setLineWidth(0.5f);
            cs.moveTo(margin, footerY + 12);
            cs.lineTo(margin + contentWidth, footerY + 12);
            cs.stroke();

            cs.setNonStrokingColor(MUTED);
            addText(cs, PDType1Font.HELVETICA, 8, margin, footerY, "Gracias por ser parte de la comunidad BeWorking.");
            addTextRightAligned(cs, PDType1Font.HELVETICA, 8, margin + contentWidth, footerY, "be-working.com");

        } finally {
            if (cs != null) {
                cs.close();
            }
        }
    }

    /**
     * Draws a green accent bar across the top of the page.
     */
    private void drawTopBar(PDPageContentStream cs, float pageWidth) throws IOException {
        fillRect(cs, 0, PDRectangle.A4.getHeight() - 6, pageWidth, 6, BRAND_GREEN);
    }

    private float drawHeaderSection(PDPageContentStream cs,
                                    PDDocument doc,
                                    InvoiceHeader header,
                                    ClientInfo clientInfo,
                                    String centerName,
                                    BigDecimal total,
                                    NumberFormat currencyFormat,
                                    float contentWidth,
                                    float height,
                                    float margin,
                                    boolean includeDetails,
                                    CompanyInfo company) throws IOException {
        float cursorY = height - margin - 10; // below the top bar

        // Brand logo
        try (InputStream logoStream = getClass().getResourceAsStream("/beworking_logo.png")) {
            if (logoStream != null) {
                byte[] logoBytes = logoStream.readAllBytes();
                PDImageXObject logoImage = PDImageXObject.createFromByteArray(doc, logoBytes, "logo");
                // Logo PNG is 577x120; render at ~130pt wide, height proportional
                float logoW = 130f;
                float logoH = logoW * logoImage.getHeight() / logoImage.getWidth();
                cs.drawImage(logoImage, margin, cursorY - logoH + 8, logoW, logoH);
            } else {
                // Fallback to text if logo not found
                cs.setNonStrokingColor(BRAND_GREEN);
                addText(cs, PDType1Font.HELVETICA_BOLD, 22, margin, cursorY, "BeWorking");
            }
        }

        // Company info to the right
        cs.setNonStrokingColor(MUTED);
        float companyX = margin + contentWidth;
        float companyY = cursorY;
        addTextRightAligned(cs, PDType1Font.HELVETICA, 8.5f, companyX, companyY, company.name());
        companyY -= 11;
        addTextRightAligned(cs, PDType1Font.HELVETICA, 8.5f, companyX, companyY, company.address());
        companyY -= 11;
        addTextRightAligned(cs, PDType1Font.HELVETICA, 8.5f, companyX, companyY, company.cityLine());
        companyY -= 11;
        addTextRightAligned(cs, PDType1Font.HELVETICA, 8.5f, companyX, companyY, company.email() + " \u00b7 " + company.nif());

        cursorY = companyY - 18;

        // Divider
        cs.setStrokingColor(BORDER);
        cs.setLineWidth(0.6f);
        cs.moveTo(margin, cursorY);
        cs.lineTo(margin + contentWidth, cursorY);
        cs.stroke();

        cursorY -= 22;

        // Invoice title badge
        String invoiceLabel = "FACTURA";
        String invoiceNumber = "#" + header.displayNumber();
        cs.setNonStrokingColor(BRAND_GREEN);
        addText(cs, PDType1Font.HELVETICA_BOLD, 9, margin, cursorY + 2, invoiceLabel);
        cs.setNonStrokingColor(INK);
        float labelWidth = PDType1Font.HELVETICA_BOLD.getStringWidth(invoiceLabel) / 1000f * 9f;
        addText(cs, PDType1Font.HELVETICA_BOLD, 14, margin + labelWidth + 6, cursorY, invoiceNumber);

        // Date on the right
        String dateLabel = header.issuedAt() != null
            ? DATE_FORMAT.format(header.issuedAt().atZone(ZoneId.systemDefault()))
            : "\u2014";
        cs.setNonStrokingColor(MUTED);
        addTextRightAligned(cs, PDType1Font.HELVETICA, 10, margin + contentWidth, cursorY, "Fecha: " + dateLabel);

        cursorY -= 28;

        if (includeDetails) {
            // Two-column layout: Client info (left) | Invoice details (right)
            float colLeft = margin;
            float colRight = margin + contentWidth * 0.55f;

            // Client card
            cs.setNonStrokingColor(BRAND_GREEN);
            addText(cs, PDType1Font.HELVETICA_BOLD, 8, colLeft, cursorY, "CLIENTE");
            cursorY -= 14;

            String clientBlock = buildClientBlock(clientInfo);
            String[] clientLines = clientBlock.split("\\r?\\n");
            float clientY = cursorY;
            cs.setNonStrokingColor(INK);
            for (String line : clientLines) {
                addText(cs, PDType1Font.HELVETICA, 9.5f, colLeft, clientY, line);
                clientY -= 13;
            }

            // Details column
            float metaY = cursorY;
            cs.setNonStrokingColor(BRAND_GREEN);
            addText(cs, PDType1Font.HELVETICA_BOLD, 8, colRight, metaY + 14, "DETALLES");
            cs.setNonStrokingColor(MUTED);
            addText(cs, PDType1Font.HELVETICA, 9.5f, colRight, metaY, "Centro:");
            cs.setNonStrokingColor(INK);
            addText(cs, PDType1Font.HELVETICA, 9.5f, colRight + 45, metaY, centerName != null ? centerName : "\u2014");
            metaY -= 13;
            cs.setNonStrokingColor(MUTED);
            addText(cs, PDType1Font.HELVETICA, 9.5f, colRight, metaY, "ID Cliente:");
            cs.setNonStrokingColor(INK);
            String clientIdText = header.clientId() != null ? header.clientId().toString() : "\u2014";
            addText(cs, PDType1Font.HELVETICA, 9.5f, colRight + 62, metaY, clientIdText);

            cursorY = Math.min(clientY, metaY) - 18;
        } else {
            cursorY -= 10;
        }

        // Divider
        cs.setStrokingColor(BORDER);
        cs.setLineWidth(0.6f);
        cs.moveTo(margin, cursorY);
        cs.lineTo(margin + contentWidth, cursorY);
        cs.stroke();
        cursorY -= 14;

        return cursorY;
    }

    private float drawTableHeader(PDPageContentStream cs,
                                  float margin,
                                  float[] colWidths,
                                  float headerRowHeight,
                                  float cursorY) throws IOException {
        float tableWidth = sum(colWidths);
        String[] headers = {"Descripci\u00f3n", "Cant.", "Precio unit.", "Importe"};

        // Table header background
        fillRect(cs, margin, cursorY - headerRowHeight, tableWidth, headerRowHeight, TABLE_HEADER_BG);

        // Green left accent on header
        fillRect(cs, margin, cursorY - headerRowHeight, 3f, headerRowHeight, BRAND_GREEN);

        float textY = cursorY - headerRowHeight + 10;
        cs.setNonStrokingColor(INK);
        addText(cs, PDType1Font.HELVETICA_BOLD, 9, margin + 10, textY, headers[0]);
        addText(cs, PDType1Font.HELVETICA_BOLD, 9, margin + colWidths[0] + 10, textY, headers[1]);
        addTextRightAligned(cs, PDType1Font.HELVETICA_BOLD, 9, margin + colWidths[0] + colWidths[1] + colWidths[2] - 10, textY, headers[2]);
        addTextRightAligned(cs, PDType1Font.HELVETICA_BOLD, 9, margin + tableWidth - 10, textY, headers[3]);

        // Bottom border of header
        cs.setStrokingColor(BRAND_GREEN);
        cs.setLineWidth(0.8f);
        cs.moveTo(margin, cursorY - headerRowHeight);
        cs.lineTo(margin + tableWidth, cursorY - headerRowHeight);
        cs.stroke();

        return cursorY - headerRowHeight - 4;
    }

    // ─── Drawing helpers ────────────────────────────────────────────────

    private static void fillRect(PDPageContentStream cs, float x, float y, float w, float h, Color color) throws IOException {
        cs.setNonStrokingColor(color);
        cs.addRect(x, y, w, h);
        cs.fill();
    }

    private static void fillRoundedRect(PDPageContentStream cs, float x, float y, float w, float h, float r, Color color) throws IOException {
        cs.setNonStrokingColor(color);
        // Approximate rounded rect with straight rect (PDFBox 2.x lacks curveTo-based rounded rects easily)
        // Use a simple approach: fill main body + small rects at corners for visual approximation
        cs.addRect(x + r, y, w - 2 * r, h);
        cs.fill();
        cs.addRect(x, y + r, w, h - 2 * r);
        cs.fill();
        // Fill corner circles
        float[][] corners = {
            {x + r, y + r},
            {x + w - r, y + r},
            {x + r, y + h - r},
            {x + w - r, y + h - r}
        };
        for (float[] c : corners) {
            drawCircle(cs, c[0], c[1], r);
        }
    }

    private static void drawCircle(PDPageContentStream cs, float cx, float cy, float r) throws IOException {
        // Approximate circle with 4 bezier curves
        float k = 0.5522848f * r;
        cs.moveTo(cx - r, cy);
        cs.curveTo(cx - r, cy + k, cx - k, cy + r, cx, cy + r);
        cs.curveTo(cx + k, cy + r, cx + r, cy + k, cx + r, cy);
        cs.curveTo(cx + r, cy - k, cx + k, cy - r, cx, cy - r);
        cs.curveTo(cx - k, cy - r, cx - r, cy - k, cx - r, cy);
        cs.fill();
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
        return value != null ? currencyFormat.format(value) : "\u2014";
    }

    private static List<String> wrapText(String text, PDType1Font font, float fontSize, float maxWidth) throws IOException {
        List<String> lines = new ArrayList<>();
        if (text == null || text.isBlank()) {
            lines.add("\u2014");
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
            return "\u2014";
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
            addressPieces.add(String.join(" \u00b7 ", locality));
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
        return parts.isEmpty() ? "\u2014" : String.join("\n", parts);
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
                                 LocalDateTime issuedAt,
                                 String cuentaCodigo) {
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
