package com.beworking.bookings;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(path = "/api/public/availability", produces = MediaType.APPLICATION_JSON_VALUE)
public class PublicAvailabilityController {

    private final BloqueoRepository bloqueoRepository;

    public PublicAvailabilityController(BloqueoRepository bloqueoRepository) {
        this.bloqueoRepository = bloqueoRepository;
    }

    @GetMapping
    public List<PublicAvailabilityResponse> getAvailability(
        @RequestParam(name = "date", required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        LocalDate date,
        @RequestParam(name = "products", required = false) List<String> productNames,
        @RequestParam(name = "centers", required = false) List<String> centerCodes
    ) {
        LocalDate effectiveDate = date != null ? date : LocalDate.now();
        LocalDateTime start = effectiveDate.atStartOfDay();
        LocalDateTime end = effectiveDate.plusDays(1).atStartOfDay();

        List<String> normalizedProducts = normalize(productNames);
        List<String> normalizedCenters = normalize(centerCodes);

        List<Bloqueo> bloqueos = bloqueoRepository.findPublicAvailability(
            normalizedProducts,
            normalizedProducts.isEmpty(),
            normalizedCenters,
            normalizedCenters.isEmpty(),
            start,
            end
        );

        return bloqueos.stream()
            .map(PublicAvailabilityResponse::from)
            .collect(Collectors.toList());
    }

    private List<String> normalize(List<String> values) {
        if (values == null || values.isEmpty()) {
            return Collections.emptyList();
        }
        return values.stream()
            .flatMap(value -> List.of(value.split(",")).stream())
            .map(String::trim)
            .filter(value -> !value.isEmpty())
            .map(value -> value.toLowerCase(Locale.ROOT))
            .distinct()
            .toList();
    }
}
