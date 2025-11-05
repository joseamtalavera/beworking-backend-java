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

@RestController
@RequestMapping(path = "/api/public", produces = MediaType.APPLICATION_JSON_VALUE)
public class PublicLookupController {

    private final CentroRepository centroRepository;
    private final ProductoRepository productoRepository;

    public PublicLookupController(CentroRepository centroRepository, ProductoRepository productoRepository) {
        this.centroRepository = centroRepository;
        this.productoRepository = productoRepository;
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

        return productoRepository.findAll().stream()
            .filter(producto -> type == null || (producto.getTipo() != null && producto.getTipo().equalsIgnoreCase(type)))
            .filter(producto -> centerCode == null || (producto.getCentroCodigo() != null && producto.getCentroCodigo().equalsIgnoreCase(centerCode)))
            .sorted(Comparator.comparing(p -> Optional.ofNullable(p.getNombre()).orElse("")))
            .map(producto -> new ProductoLookupResponse(
                producto.getId(),
                producto.getNombre(),
                producto.getTipo(),
                producto.getCentroCodigo()
            ))
            .collect(Collectors.toList());
    }

    public record CentroLookupResponse(Long id, String name, String code, String city) {}
    public record ProductoLookupResponse(Long id, String name, String type, String centerCode) {}
}



