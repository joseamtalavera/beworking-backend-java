package com.beworking.invoices;

import java.io.IOException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/invoices")
public class InvoicePdfController {

    private final InvoicePdfService pdfService;

    public InvoicePdfController(InvoicePdfService pdfService) {
        this.pdfService = pdfService;
    }

    @GetMapping("/{id}/pdf")
    public ResponseEntity<byte[]> renderPdf(@PathVariable("id") Long id) throws IOException {
        byte[] pdfBytes = pdfService.generatePdf(id);
        if (pdfBytes == null) {
            return ResponseEntity.notFound().build();
        }

        String displayNumber = pdfService.getDisplayNumber(id);
        String filename = "invoice-" + displayNumber + ".pdf";
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=" + filename)
            .contentType(MediaType.APPLICATION_PDF)
            .body(pdfBytes);
    }
}
