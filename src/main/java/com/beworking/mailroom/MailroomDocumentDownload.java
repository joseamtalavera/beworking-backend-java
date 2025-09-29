package com.beworking.mailroom;

import org.springframework.core.io.Resource;

public record MailroomDocumentDownload(
        Resource resource,
        String originalFileName,
        String contentType
) {
}
