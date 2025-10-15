package com.beworking.mailroom;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/mailroom/documents")
public class MailroomDocumentController {

    private final MailroomDocumentService service;

    public MailroomDocumentController(MailroomDocumentService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<List<MailroomDocumentResponse>> listDocuments() {
        return ResponseEntity.ok(service.listRecentDocuments());
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<MailroomDocumentResponse> uploadDocument(
            @RequestPart("file") MultipartFile file,
            @RequestParam(value = "title", required = false) String title,
            @RequestParam(value = "receivedAt", required = false) String receivedAt,
            @RequestParam(value = "tenantId", required = false) String tenantId,
            @RequestParam(value = "pages", required = false) Integer pages,
            @RequestParam(value = "avatarColor", required = false) String avatarColor,
            @RequestParam(value = "contactEmail", required = false) String contactEmail,
            Authentication authentication
    ) {
        MailroomDocumentResponse created = service.createDocument(
                file,
                title,
                resolveSender(authentication),
                parseInstant(receivedAt),
                tenantId,
                pages,
                StringUtils.hasText(avatarColor) ? avatarColor : null,
                contactEmail
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PostMapping("/{id}/notify")
    public ResponseEntity<MailroomDocumentResponse> markNotified(@PathVariable("id") UUID documentId) {
        MailroomDocumentResponse updated = service.markDocumentNotified(documentId);
        return ResponseEntity.ok(updated);
    }

    @PostMapping("/{id}/view")
    public ResponseEntity<MailroomDocumentResponse> markViewed(@PathVariable("id") UUID documentId) {
        MailroomDocumentResponse updated = service.markDocumentViewed(documentId);
        return ResponseEntity.ok(updated);
    }

    @GetMapping("/{id}/download")
    public ResponseEntity<Resource> downloadDocument(@PathVariable("id") UUID documentId) {
        MailroomDocumentDownload download = service.getDocumentDownload(documentId);
        MediaType mediaType = MediaType.parseMediaType(download.contentType());
        ContentDisposition contentDisposition = ContentDisposition.inline()
                .filename(download.originalFileName(), StandardCharsets.UTF_8)
                .build();

        return ResponseEntity.ok()
                .contentType(mediaType)
                .header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition.toString())
                .body(download.resource());
    }

    private Instant parseInstant(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    private String resolveSender(Authentication authentication) {
        if (authentication == null) {
            return "System";
        }
        if (StringUtils.hasText(authentication.getName())) {
            return authentication.getName();
        }
        Object principal = authentication.getPrincipal();
        return principal != null ? principal.toString() : "System";
    }
}
