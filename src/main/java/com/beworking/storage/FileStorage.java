package com.beworking.storage;

import org.springframework.core.io.Resource;
import org.springframework.web.multipart.MultipartFile;

/**
 * Abstraction over mailroom file storage. The local impl (dev profile) writes
 * to disk; the S3 impl (non-dev) stores private objects in a bucket. The
 * mailroom download endpoint is already authorization-gated, so swapping the
 * backing store does not change access control.
 */
public interface FileStorage {

    StoredFile store(MultipartFile file);

    Resource loadAsResource(String storedFileName);
}