package com.beworking.uploads;

import com.beworking.auth.User;
import com.beworking.auth.UserRepository;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/uploads")
@Profile("dev")
public class UploadController {
    private final LocalMediaStorageService storage;
    private final UserRepository userRepository;

    public UploadController(LocalMediaStorageService storage, UserRepository userRepository) {
        this.storage = storage;
        this.userRepository = userRepository;
    }

    private void requireAdmin(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        userRepository.findByEmail(authentication.getName())
            .filter(User::isAdmin)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN));
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public UploadResponse upload(@RequestPart("file") MultipartFile file, Authentication authentication) throws IOException {
        requireAdmin(authentication);
        if (file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Empty file");
        }
        String url = storage.store(file);
        return new UploadResponse(url);
    }

    public record UploadResponse(String url) {}
    
}
