package com.beworking.invoices;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/invoices")
public class InvoiceController {

    private final InvoiceRepository invoiceRepository;
    private final InvoiceService invoiceService;

    public InvoiceController(InvoiceRepository invoiceRepository, InvoiceService invoiceService) {
        this.invoiceRepository = invoiceRepository;
        this.invoiceService = invoiceService;
    }

    @GetMapping
    public ResponseEntity<Page<InvoiceProjection>> list(
        @RequestParam(value = "page", defaultValue = "0") int page,
        @RequestParam(value = "size", defaultValue = "25") int size
    ) {
        if (size <= 0 || size > 200) {
            size = 25;
        }
        Pageable pageable = PageRequest.of(page, size);
        Page<InvoiceProjection> invoices = invoiceRepository.findInvoices(pageable);
        return ResponseEntity.ok(invoices);
    }

    @GetMapping("/pdf-url")
    public ResponseEntity<String> pdfUrl(@RequestParam("id") Long id) {
        return invoiceService.resolvePdfUrl(id)
            .map(uri -> ResponseEntity.ok(uri.toString()))
            .orElseGet(() -> ResponseEntity.notFound().build());
    }
}


