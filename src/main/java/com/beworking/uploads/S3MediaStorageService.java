package com.beworking.uploads;

import java.io.IOException;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

@Service
@Profile("!dev")
public class S3MediaStorageService implements MediaStorageService {

    private final S3Client s3Client;
    private final String bucket;
    private final String region;

    public S3MediaStorageService(
            @Value("${media.s3.bucket}") String bucket,
            @Value("${media.s3.region:eu-north-1}") String region) {
        this.bucket = bucket;
        this.region = region;
        this.s3Client = S3Client.builder()
                .region(Region.of(region))
                .build();
    }

    @Override
    public String store(MultipartFile file) throws IOException {
        String key = "catalog/" + UUID.randomUUID() + "-" + StringUtils.cleanPath(file.getOriginalFilename());
        s3Client.putObject(
                PutObjectRequest.builder()
                        .bucket(bucket)
                        .key(key)
                        .contentType(file.getContentType())
                        .build(),
                RequestBody.fromBytes(file.getBytes()));
        return "https://" + bucket + ".s3." + region + ".amazonaws.com/" + key;
    }
}
