package com.beworking.bookings;

import com.beworking.auth.User;
import com.beworking.auth.UserRepository;
import com.beworking.contacts.ContactProfile;
import com.beworking.contacts.ContactProfileRepository;
import com.beworking.rooms.Room;
import com.beworking.rooms.RoomRepository;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/bookings/lookups")
public class BookingLookupController {

    private final UserRepository userRepository;
    private final ContactProfileRepository contactRepository;
    private final CentroRepository centroRepository;
    private final ProductoRepository productoRepository;
    private final RoomRepository roomRepository;

    public BookingLookupController(UserRepository userRepository,
                                   ContactProfileRepository contactRepository,
                                   CentroRepository centroRepository,
                                   ProductoRepository productoRepository,
                                   RoomRepository roomRepository) {
        this.userRepository = userRepository;
        this.contactRepository = contactRepository;
        this.centroRepository = centroRepository;
        this.productoRepository = productoRepository;
        this.roomRepository = roomRepository;
    }

    @GetMapping("/contacts")
    public ResponseEntity<List<ContactLookupResponse>> contacts(Authentication authentication,
                                                                @RequestParam(name = "search", required = false) String search,
                                                                @RequestParam(name = "tenantType", required = false) String tenantType) {
        User user = requireAuthenticatedUser(authentication);
        boolean isAdmin = user.getRole() == User.Role.ADMIN;
        boolean isUser = user.getRole() == User.Role.USER;
        if (!isAdmin && !isUser) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }

        if (!isAdmin) {
            Long tenantId = user.getTenantId();
            if (tenantId == null) {
                return ResponseEntity.ok(Collections.emptyList());
            }
            return contactRepository.findById(tenantId)
                .map(contact -> ResponseEntity.ok(List.of(mapToContact(contact))))
                .orElseGet(() -> ResponseEntity.ok(Collections.emptyList()));
        }

        List<ContactProfile> contacts;
        if (search != null && !search.isBlank()) {
            contacts = contactRepository.searchContacts(search.trim());
        } else {
            contacts = contactRepository.findTop50ByOrderByNameAsc();
        }

        List<ContactLookupResponse> payload = contacts.stream()
            .filter(contact -> tenantType == null || tenantType.isBlank()
                || (contact.getTenantType() != null && contact.getTenantType().equalsIgnoreCase(tenantType)))
            .limit(50)
            .map(this::mapToContact)
            .collect(Collectors.toList());

        return ResponseEntity.ok(payload);
    }

    @GetMapping("/centros")
    public ResponseEntity<List<CentroLookupResponse>> centros(Authentication authentication) {
        User user = requireAuthenticatedUser(authentication);
        if (user.getRole() != User.Role.ADMIN && user.getRole() != User.Role.USER) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }

        List<Long> allowedIds = List.of(1L, 8L);

        List<CentroLookupResponse> payload = centroRepository.findAll().stream()
            .filter(centro -> allowedIds.contains(centro.getId()))
            .sorted(Comparator.comparing(c -> Optional.ofNullable(c.getNombre()).orElse("")))
            .map(centro -> new CentroLookupResponse(centro.getId(), centro.getNombre(), centro.getCodigo()))
            .collect(Collectors.toList());

        return ResponseEntity.ok(payload);
    }

    @GetMapping("/productos")
    public ResponseEntity<List<ProductoLookupResponse>> productos(Authentication authentication,
                                                                  @RequestParam(name = "type", required = false) String type,
                                                                  @RequestParam(name = "centerCode", required = false) String centerCode) {
        User user = requireAuthenticatedUser(authentication);
        if (user.getRole() != User.Role.ADMIN && user.getRole() != User.Role.USER) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }

        // Try rooms table first (has pricing data)
        List<ProductoLookupResponse> fromRooms = roomRepository.findAll().stream()
            .filter(room -> type == null || (room.getType() != null && room.getType().equalsIgnoreCase(type)))
            .filter(room -> centerCode == null || (room.getCentroCode() != null && room.getCentroCode().equalsIgnoreCase(centerCode)))
            .sorted(Comparator.comparing(room -> Optional.ofNullable(room.getCode()).orElse("")))
            .map(room -> {
                Long productoId = productoRepository.findByNombreIgnoreCase(room.getCode())
                    .map(Producto::getId)
                    .orElse(room.getId());
                return new ProductoLookupResponse(
                    productoId,
                    room.getCode(),
                    room.getType(),
                    room.getCentroCode(),
                    room.getHeroImage(),
                    room.getSubtitle(),
                    room.getCapacity(),
                    room.getPriceFrom(),
                    room.getPriceUnit(),
                    room.getRatingAverage(),
                    room.getRatingCount(),
                    room.isInstantBooking(),
                    room.getDescription(),
                    room.getAmenities().stream().map(a -> a.getAmenityCode()).toList(),
                    room.getTags() == null || room.getTags().isBlank()
                        ? List.of()
                        : List.of(room.getTags().split("\\s*,\\s*")),
                    room.getImages().stream().map(img -> img.getUrl()).toList()
                );
            })
            .collect(Collectors.toList());

        if (!fromRooms.isEmpty()) {
            return ResponseEntity.ok(fromRooms);
        }

        // Fallback to productos table
        List<ProductoLookupResponse> payload = productoRepository.findAll().stream()
            .filter(producto -> type == null || (producto.getTipo() != null && producto.getTipo().equalsIgnoreCase(type)))
            .filter(producto -> centerCode == null || (producto.getCentroCodigo() != null && producto.getCentroCodigo().equalsIgnoreCase(centerCode)))
            .sorted(Comparator.comparing(producto -> Optional.ofNullable(producto.getNombre()).orElse("")))
            .map(producto -> new ProductoLookupResponse(
                producto.getId(),
                producto.getNombre(),
                producto.getTipo(),
                producto.getCentroCodigo(),
                null, null, null, null, null, null, null, null, null, null, null, List.of()
            ))
            .collect(Collectors.toList());

        return ResponseEntity.ok(payload);
    }

    private User requireAuthenticatedUser(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }

        Optional<User> userOpt = userRepository.findByEmail(authentication.getName());
        if (userOpt.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }

        return userOpt.get();
    }

    private ContactLookupResponse mapToContact(ContactProfile contact) {
        String displayName = firstNonBlank(
            contact.getName(),
            contact.getBillingName(),
            contact.getContactName(),
            combineName(contact.getRepresentativeFirstName(), contact.getRepresentativeLastName())
        );
        String displayEmail = firstNonBlank(
            contact.getEmailPrimary(),
            contact.getEmailSecondary(),
            contact.getEmailTertiary(),
            contact.getRepresentativeEmail()
        );
        return new ContactLookupResponse(
            contact.getId(),
            displayName,
            displayEmail,
            contact.getTenantType(),
            contact.getAvatar()
        );
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }

    private static String combineName(String first, String last) {
        if ((first == null || first.isBlank()) && (last == null || last.isBlank())) return null;
        if (first == null || first.isBlank()) return last;
        if (last == null || last.isBlank()) return first;
        return first + " " + last;
    }

    public record ContactLookupResponse(Long id, String name, String email, String tenantType, String avatar) { }

    public record CentroLookupResponse(Long id, String name, String code) { }

    public record ProductoLookupResponse(Long id, String name, String type, String centerCode, String heroImage,
                                         String subtitle,
                                         Integer capacity, java.math.BigDecimal priceFrom, String priceUnit,
                                         java.math.BigDecimal ratingAverage, Integer ratingCount, Boolean instantBooking,
                                         String description, java.util.List<String> amenities, java.util.List<String> tags, java.util.List<String> images) { }
}
