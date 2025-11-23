package com.beworking.bookings;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.beworking.rooms.RoomRepository;

@RestController
@RequestMapping(path = "/api/public", produces = MediaType.APPLICATION_JSON_VALUE)
public class PublicLookupController {

    private final CentroRepository centroRepository;
    private final ProductoRepository productoRepository;
    private final RoomRepository roomRepository;

    public PublicLookupController(
        CentroRepository centroRepository,
        ProductoRepository productoRepository,
        RoomRepository roomRepository) {
        this.centroRepository = centroRepository;
        this.productoRepository = productoRepository;
        this.roomRepository = roomRepository;
    }

    @GetMapping("/centros")
    public List<CentroLookupResponse> centros() {

        List<Long> allowedIds = List.of(1L, 8L); // do not understand.
        
        return centroRepository.findAll().stream()
            .filter(centro -> allowedIds.contains(centro.getId()))
            .sorted(Comparator.comparing(c -> Optional.ofNullable(c.getNombre()).orElse("")))
            .map(centro -> new CentroLookupResponse(centro.getId(), centro.getNombre(), centro.getCodigo(), centro.getLocalidad()))
            .collect(Collectors.toList());
    }

    @GetMapping("/productos")
    public List<ProductoLookupResponse> productos(
        @RequestParam(name = "type", required = false) String type,
        @RequestParam(name = "centerCode", required = false) String centerCode) {

        String typeFilter = type == null ? null : type.toLowerCase();
        String centerFilter = centerCode == null ? null : centerCode.toLowerCase();

        List<ProductoLookupResponse> fromRooms = roomRepository.findAll().stream()
            .filter(room -> typeFilter == null || (room.getType() != null && room.getType().toLowerCase().equals(typeFilter)))
            .filter(room -> centerFilter == null || (room.getCentroCode() != null && room.getCentroCode().toLowerCase().equals(centerFilter)))
            .sorted(Comparator.comparing(room -> Optional.ofNullable(room.getCode()).orElse("")))
            .map(room -> new ProductoLookupResponse(
                room.getId(),
                room.getCode(),
                room.getType(),
                room.getCentroCode(),
                room.getHeroImage(),
                room.getCapacity(),
                room.getPriceFrom(),
                room.getPriceUnit(),
                room.getRatingAverage(),
                room.getRatingCount(),
                room.isInstantBooking()
            ))
            .collect(Collectors.toList());

        if (!fromRooms.isEmpty()) {
            return fromRooms;
        }

        return productoRepository.findAll().stream()
            .filter(producto -> typeFilter == null || (producto.getTipo() != null && producto.getTipo().equalsIgnoreCase(typeFilter)))
            .filter(producto -> centerFilter == null || (producto.getCentroCodigo() != null && producto.getCentroCodigo().equalsIgnoreCase(centerFilter)))
            .sorted(Comparator.comparing(p -> Optional.ofNullable(p.getNombre()).orElse("")))
            .map(producto -> new ProductoLookupResponse(
                producto.getId(),
                producto.getNombre(),
                producto.getTipo(),
                producto.getCentroCodigo(),
                null,
                null,
                null,
                null,
                null,
                null,
                null
            ))
            .collect(Collectors.toList());
    }

    public record CentroLookupResponse(Long id, String name, String code, String city) {}
    public record ProductoLookupResponse(Long id, String name, String type, String centerCode, String heroImage,
                                         Integer capacity, java.math.BigDecimal priceFrom, String priceUnit,
                                         java.math.BigDecimal ratingAverage, Integer ratingCount, Boolean instantBooking) {}
}
