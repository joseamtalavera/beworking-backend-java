package com.beworking.invoices;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/invoices")
public class InvoicePdfController {

    private final JdbcTemplate jdbcTemplate;

    public InvoicePdfController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @GetMapping("/{id}/pdf")
    public ResponseEntity<byte[]> renderPdf(@PathVariable("id") Long id) throws IOException {
        // Fetch invoice header
        final String[] header = new String[8];
        jdbcTemplate.query(
            "SELECT id, idfactura, idcliente, idcentro, descripcion, total, iva, creacionfecha FROM beworking.facturas WHERE id = ?",
            rs -> {
                header[0] = rs.getString(1);
                header[1] = rs.getString(2);
                header[2] = rs.getString(3);
                header[3] = rs.getString(4);
                header[4] = rs.getString(5);
                header[5] = rs.getString(6);
                header[6] = rs.getString(7);
                header[7] = rs.getString(8);
            }, id
        );

        if (header[0] == null) {
            return ResponseEntity.notFound().build();
        }

        // Build PDF
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);

            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                float y = page.getMediaBox().getHeight() - 72;
                cs.beginText();
                cs.setFont(PDType1Font.HELVETICA_BOLD, 18);
                cs.newLineAtOffset(72, y);
                cs.showText("BeWorking - Invoice");
                cs.endText();

                y -= 24;
                cs.beginText();
                cs.setFont(PDType1Font.HELVETICA, 12);
                cs.newLineAtOffset(72, y);
                cs.showText("Invoice #: " + (header[1] != null ? header[1] : header[0]));
                cs.endText();

                y -= 16;
                cs.beginText();
                cs.setFont(PDType1Font.HELVETICA, 12);
                cs.newLineAtOffset(72, y);
                cs.showText("Client ID: " + header[2] + "   Center: " + header[3]);
                cs.endText();

                y -= 16;
                cs.beginText();
                cs.setFont(PDType1Font.HELVETICA, 12);
                cs.newLineAtOffset(72, y);
                cs.showText("Issued: " + (header[7] != null ? header[7] : LocalDateTime.now().format(DateTimeFormatter.ISO_DATE)));
                cs.endText();

                y -= 24;
                cs.beginText();
                cs.setFont(PDType1Font.HELVETICA_BOLD, 12);
                cs.newLineAtOffset(72, y);
                cs.showText("Description");
                cs.endText();

                y -= 16;
                cs.beginText();
                cs.setFont(PDType1Font.HELVETICA, 12);
                cs.newLineAtOffset(72, y);
                cs.showText(header[4] != null ? header[4] : "—");
                cs.endText();

                y -= 24;
                cs.beginText();
                cs.setFont(PDType1Font.HELVETICA_BOLD, 12);
                cs.newLineAtOffset(72, y);
                cs.showText("Lines");
                cs.endText();

                final float[] posY = new float[] { y - 16 };
                jdbcTemplate.query(
                    "SELECT conceptodesglose, cantidaddesglose, precioundesglose, totaldesglose FROM beworking.facturasdesglose WHERE idfacturadesglose = (SELECT idfactura FROM beworking.facturas WHERE id = ?) ORDER BY id",
                    new RowCallbackHandler() {
                        @Override
                        public void processRow(ResultSet rs) throws SQLException {
                            try {
                                cs.beginText();
                                cs.setFont(PDType1Font.HELVETICA, 11);
                                cs.newLineAtOffset(72, posY[0]);
                                String line = String.format("%s  x%s  @%s  = %s",
                                    rs.getString(1),
                                    rs.getString(2),
                                    rs.getString(3),
                                    rs.getString(4)
                                );
                                cs.showText(line);
                                cs.endText();
                                posY[0] -= 14f;
                            } catch (IOException e) {
                                throw new SQLException(e);
                            }
                        }
                    }, id
                );

                posY[0] -= 8;
                cs.beginText();
                cs.setFont(PDType1Font.HELVETICA_BOLD, 12);
                cs.newLineAtOffset(72, posY[0]);
                cs.showText("VAT: " + (header[6] != null ? header[6] + "%" : "—") + "    Total: " + (header[5] != null ? header[5] : "—"));
                cs.endText();
            }

            doc.save(out);

            String filename = "invoice-" + header[1] + ".pdf";
            return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=" + filename)
                .contentType(MediaType.APPLICATION_PDF)
                .body(out.toByteArray());
        }
    }
}


