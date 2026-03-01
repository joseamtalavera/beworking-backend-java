package com.beworking.uploads;

import java.io.IOException;
import org.springframework.web.multipart.MultipartFile;

public interface MediaStorageService {
    /** Store the file and return the public URL to access it. */
    String store(MultipartFile file) throws IOException;
}
