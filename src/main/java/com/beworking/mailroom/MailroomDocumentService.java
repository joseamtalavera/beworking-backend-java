package com.beworking.mailroom;

import com.beworking.storage.FileStorageService;
import com.beworking.storage.StoredFile;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@Service
public class MailroomDocumentService {

    private static final String[] DEFAULT_AVATAR_COLORS = {
            "#facc15",
            "#38bdf8",
            "#f472b6",
            "#c4b5fd",
            "#f97316"
    };

    private final MailroomDocumentRepository repository;
    private final FileStorageService fileStorageService;

    public MailroomDocumentService(MailroomDocumentRepository repository, FileStorageService fileStorageService) {
        this.repository = repository;
        this.fileStorageService = fileStorageService;
    }

    public List<MailroomDocumentResponse> listRecentDocuments() {
        return repository.findRecentDocuments().stream()
                .map(MailroomDocumentResponse::fromEntity)
                .toList();
    }

    @Transactional
    public MailroomDocumentResponse createDocument(
            MultipartFile file,
            String title,
            String sender,
            Instant receivedAt,
            String tenantId,
            Integer pages,
            String avatarColor
    ) {
        StoredFile storedFile = fileStorageService.store(file);

        MailroomDocument document = new MailroomDocument();
        document.setTitle(resolveTitle(title, storedFile.originalFileName()));
        document.setSender(StringUtils.hasText(sender) ? sender : null);
        document.setReceivedAt(Optional.ofNullable(receivedAt).orElse(Instant.now()));
        document.setStatus(MailroomDocumentStatus.SCANNED);
        document.setPages(pages);
        document.setAvatarColor(resolveAvatarColor(avatarColor));
        document.setStoredFileName(storedFile.storedFileName());
        document.setOriginalFileName(storedFile.originalFileName());
        document.setContentType(storedFile.contentType());
        document.setFileSizeBytes(storedFile.sizeInBytes());

        if (StringUtils.hasText(tenantId)) {
            try {
                document.setTenantId(UUID.fromString(tenantId));
            } catch (IllegalArgumentException ignored) {
                // leave tenant unset if parsing fails
            }
        }

        MailroomDocument persisted = repository.save(document);
        return MailroomDocumentResponse.fromEntity(persisted);
    }

    @Transactional
    public MailroomDocumentResponse markDocumentNotified(UUID documentId) {
        MailroomDocument document = getDocumentOrThrow(documentId);
        document.setStatus(MailroomDocumentStatus.NOTIFIED);
        document.setLastNotifiedAt(Instant.now());
        MailroomDocument saved = repository.save(document);
        return MailroomDocumentResponse.fromEntity(saved);
    }

    @Transactional
    public MailroomDocumentResponse markDocumentViewed(UUID documentId) {
        MailroomDocument document = getDocumentOrThrow(documentId);
        document.setStatus(MailroomDocumentStatus.VIEWED);
        MailroomDocument saved = repository.save(document);
        return MailroomDocumentResponse.fromEntity(saved);
    }

    private String resolveTitle(String providedTitle, String originalFilename) {
        if (StringUtils.hasText(providedTitle)) {
            return providedTitle.trim();
        }
        if (StringUtils.hasText(originalFilename)) {
            return originalFilename;
        }
        return "Scanned document";
    }

    private String resolveAvatarColor(String providedColor) {
        if (StringUtils.hasText(providedColor)) {
            return providedColor;
        }
        int index = Math.abs(UUID.randomUUID().hashCode()) % DEFAULT_AVATAR_COLORS.length;
        return DEFAULT_AVATAR_COLORS[index];
    }

    private MailroomDocument getDocumentOrThrow(UUID documentId) {
        return repository.findById(documentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Mailroom document not found"));
    }

    public MailroomDocumentDownload getDocumentDownload(UUID documentId) {
        MailroomDocument document = getDocumentOrThrow(documentId);
        var resource = fileStorageService.loadAsResource(document.getStoredFileName());
        String contentType = StringUtils.hasText(document.getContentType())
                ? document.getContentType()
                : MediaType.APPLICATION_OCTET_STREAM_VALUE;
        String originalName = StringUtils.hasText(document.getOriginalFileName())
                ? document.getOriginalFileName()
                : document.getTitle();
        return new MailroomDocumentDownload(resource, originalName, contentType);
    }
}
