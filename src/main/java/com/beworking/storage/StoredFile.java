package com.beworking.storage;

public record StoredFile(
        String storedFileName,
        String originalFileName,
        String contentType,
        long sizeInBytes
) {
}
