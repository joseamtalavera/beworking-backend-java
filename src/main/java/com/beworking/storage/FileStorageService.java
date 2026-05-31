 package com.beworking.storage;

  import java.io.IOException;
  import java.io.InputStream;
  import java.net.MalformedURLException;
  import java.nio.file.Files;
  import java.nio.file.Path;
  import java.nio.file.Paths;
  import java.nio.file.StandardCopyOption;
  import java.util.Objects;
  import java.util.UUID;
  import org.springframework.context.annotation.Profile;
  import org.springframework.core.io.Resource;
  import org.springframework.core.io.UrlResource;
  import org.springframework.stereotype.Service;
  import org.springframework.util.StringUtils;
  import org.springframework.web.multipart.MultipartFile;

  /**
   * Local-disk mailroom storage. Active only under the dev profile; staging/prod
   * (profile "prod") use {@link S3FileStorageService}. NOTE: container disk is
   * ephemeral, so this is for local development only — never durable in ECS.
   */
  @Service
  @Profile("dev")
  public class FileStorageService implements FileStorage {

      private final Path storageLocation;

      public FileStorageService(FileStorageProperties properties) {
          Path configured = Paths.get(properties.getLocation()).toAbsolutePath().normalize();
          try {
              Files.createDirectories(configured);
          } catch (IOException e) {
              throw new FileStorageException("Could not create upload directory " + configured, e);
          }
          this.storageLocation = configured;
      }

      @Override
      public StoredFile store(MultipartFile file) {
          if (file == null || file.isEmpty()) {
              throw new FileStorageException("Cannot store empty file");
          }

          String originalFilename = StringUtils.cleanPath(Objects.requireNonNullElse(file.getOriginalFilename(), "upload"));
          String extension = extractExtension(originalFilename);
          String storedFileName = UUID.randomUUID() + extension;

          Path targetLocation = storageLocation.resolve(storedFileName);
          try (InputStream inputStream = file.getInputStream()) {
              Files.copy(inputStream, targetLocation, StandardCopyOption.REPLACE_EXISTING);
          } catch (IOException e) {
              throw new FileStorageException("Failed to store file " + originalFilename, e);
          }

          return new StoredFile(storedFileName, originalFilename, file.getContentType(), file.getSize());
      }

      private String extractExtension(String filename) {
          int lastDot = filename.lastIndexOf('.');
          if (lastDot == -1) {
              return "";
          }
          return filename.substring(lastDot);
      }

      @Override
      public Resource loadAsResource(String storedFileName) {
          try {
              Path filePath = storageLocation.resolve(storedFileName).normalize();
              Resource resource = new UrlResource(filePath.toUri());
              if (resource.exists() && resource.isReadable()) {
                  return resource;
              }
              throw new FileStorageException("File not found: " + storedFileName);
          } catch (MalformedURLException e) {
              throw new FileStorageException("Invalid file path for: " + storedFileName, e);
          }
      }
  }