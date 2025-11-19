package com.beworking.rooms.catalog;

import com.beworking.auth.User;
import com.beworking.auth.UserRepository;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST API for managing the admin space catalog. All endpoints are restricted to admins
 * and delegate the business logic to {@link SpaceCatalogService}.
 */
@RestController
@RequestMapping("/api/catalog/spaces")
public class SpaceCatalogController {

    private final SpaceCatalogService service;
    private final UserRepository userRepository;

    public SpaceCatalogController(SpaceCatalogService service, UserRepository userRepository) {
        this.service = service;
        this.userRepository = userRepository;
    }

    /**
     * Verifies the current authentication belongs to an admin user.
     */
    private boolean isAdmin(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return false;
        }
        return userRepository.findByEmail(authentication.getName())
            .map(User::isAdmin)
            .orElse(false);
    }

    /**
     * Lists all catalog spaces. Returns 403 if the caller is not an admin.
     */
    @GetMapping
    public ResponseEntity<List<SpaceCatalogDto>> list(Authentication authentication) {
        if (!isAdmin(authentication)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        return ResponseEntity.ok(service.listSpaces());
    }

    /**
     * Retrieves a single space by id, returning 404 when not found.
     */
    @GetMapping("/{id}")
    public ResponseEntity<SpaceCatalogDto> get(@PathVariable Long id, Authentication authentication) {
        if (!isAdmin(authentication)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        return service.findById(id)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Creates a new space record using the posted payload.
     */
    @PostMapping
    public ResponseEntity<SpaceCatalogDto> create(@RequestBody SpaceCatalogDto payload, Authentication authentication) {
        if (!isAdmin(authentication)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        SpaceCatalogDto saved = service.save(payload);
        return ResponseEntity.status(HttpStatus.CREATED).body(saved);
    }

    /**
     * Updates an existing space identified by the path id.
     */
    @PutMapping("/{id}")
    public ResponseEntity<SpaceCatalogDto> update(@PathVariable Long id,
                                                  @RequestBody SpaceCatalogDto payload,
                                                  Authentication authentication) {
        if (!isAdmin(authentication)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        SpaceCatalogDto saved = service.save(
            new SpaceCatalogDto(
                id,
                payload.code(),
                payload.centroCode(),
                payload.displayName(),
                payload.subtitle(),
                payload.description(),
                payload.address(),
                payload.city(),
                payload.postalCode(),
                payload.country(),
                payload.region(),
                payload.type(),
                payload.status(),
                payload.creationDate(),
                payload.sizeSqm(),
                payload.capacity(),
                payload.priceFrom(),
                payload.priceUnit(),
                payload.priceHourMin(),
                payload.priceHourMed(),
                payload.priceHourMax(),
                payload.priceDay(),
                payload.priceMonth(),
                payload.wifiCredentials(),
                payload.sortOrder(),
                payload.ratingAverage(),
                payload.ratingCount(),
                payload.instantBooking(),
                payload.tags(),
                payload.heroImage(),
                payload.images(),
                payload.amenities()
            )
        );
        return ResponseEntity.ok(saved);
    }

    /**
     * Deletes a space by id.
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id, Authentication authentication) {
        if (!isAdmin(authentication)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}
