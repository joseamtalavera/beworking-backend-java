package com.beworking.storage;
  
  import java.io.IOException;
  import java.util.Objects;
  import java.util.UUID;
  import org.springframework.beans.factory.annotation.Value;
  import org.springframework.context.annotation.Profile;
  import org.springframework.core.io.ByteArrayResource;
  import org.springframework.core.io.Resource;
  import org.springframework.stereotype.Service;
  import org.springframework.util.StringUtils;
  import org.springframework.web.multipart.MultipartFile;
  import software.amazon.awssdk.core.sync.RequestBody;
  import software.amazon.awssdk.regions.Region;
  import software.amazon.awssdk.services.s3.S3Client;
  import software.amazon.awssdk.services.s3.model.GetObjectRequest;
  import software.amazon.awssdk.services.s3.model.PutObjectRequest;

  /**
   * Durable mailroom storage backed by a PRIVATE S3 bucket. Active for all
   * non-dev profiles (staging/prod run profile "prod"). Objects are stored under
   * the "mailroom/" prefix with no public access; bytes are streamed back through
   * the already authorization-gated download endpoint. Credentials come from the
   * ECS task role — no static keys.
   */
  @Service
  @Profile("!dev")
  public class S3FileStorageService implements FileStorage {

      private static final String KEY_PREFIX = "mailroom/";

      private final S3Client s3Client;
      private final String bucket;

      public S3FileStorageService(
              @Value("${mailroom.s3.bucket:}") String bucket,
              @Value("${mailroom.s3.region:eu-north-1}") String region) {
          this.bucket = bucket;
          this.s3Client = S3Client.builder()
                  .region(Region.of(region))
                  .build();
      }

      @Override
      public StoredFile store(MultipartFile file) {
          if (file == null || file.isEmpty()) {
              throw new FileStorageException("Cannot store empty file");
          }
          String originalFilename = StringUtils.cleanPath(
                  Objects.requireNonNullElse(file.getOriginalFilename(), "upload"));
          String storedFileName = UUID.randomUUID() + extractExtension(originalFilename);
          try {
              s3Client.putObject(
                      PutObjectRequest.builder()
                              .bucket(bucket)
                              .key(KEY_PREFIX + storedFileName)
                              .contentType(file.getContentType())
                              .build(),
                      RequestBody.fromBytes(file.getBytes()));
          } catch (IOException e) {
              throw new FileStorageException("Failed to store file " + originalFilename, e);
          }
          return new StoredFile(storedFileName, originalFilename, file.getContentType(), file.getSize());
      }

      @Override
      public Resource loadAsResource(String storedFileName) {
          try {
              byte[] bytes = s3Client.getObjectAsBytes(
                      GetObjectRequest.builder()
                              .bucket(bucket)
                              .key(KEY_PREFIX + storedFileName)
                              .build())
                      .asByteArray();
              return new ByteArrayResource(bytes);
          } catch (Exception e) {
              throw new FileStorageException("File not found: " + storedFileName, e);
          }
      }

      private String extractExtension(String filename) {
          int lastDot = filename.lastIndexOf('.');
          return lastDot == -1 ? "" : filename.substring(lastDot);
      }
  }