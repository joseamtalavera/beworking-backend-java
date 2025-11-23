package com.beworking.uploads;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

@Service
@Profile("dev")
public class LocalMediaStorageService {
    @Value("${media.local.root:uploads/catalog}")
    private String rootDir;
    public String store(MultipartFile file) throws IOException {
        Path root = Path.of(rootDir).toAbsolutePath();
        Files.createDirectories(root);
        String filename = UUID.randomUUID() + "-" + StringUtils.cleanPath(file.getOriginalFilename());
        Path target = root.resolve(filename);
        file.transferTo(target.toFile());
        return "/uploads/" + filename; // the URL returned to the client
    }
}
