package com.beworking.bookings;

import com.beworking.contacts.ContactProfile;
import com.beworking.contacts.ContactProfileRepository;
import com.beworking.subscriptions.Subscription;
import com.beworking.subscriptions.SubscriptionRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(path = "/api/public/availability", produces = MediaType.APPLICATION_JSON_VALUE)
public class PublicAvailabilityController {

    private static final Logger LOGGER = LoggerFactory.getLogger(PublicAvailabilityController.class);

    private final BloqueoRepository bloqueoRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final ProductoRepository productoRepository;
    private final ContactProfileRepository contactProfileRepository;

    public PublicAvailabilityController(
        BloqueoRepository bloqueoRepository,
        SubscriptionRepository subscriptionRepository,
        ProductoRepository productoRepository,
        ContactProfileRepository contactProfileRepository
    ) {
        this.bloqueoRepository = bloqueoRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.productoRepository = productoRepository;
        this.contactProfileRepository = contactProfileRepository;
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

        List<PublicAvailabilityResponse> responses = bloqueos.stream()
            .map(PublicAvailabilityResponse::from)
            .collect(Collectors.toCollection(ArrayList::new));

        responses.addAll(subscriptionResponses(effectiveDate, normalizedProducts, normalizedCenters, start, end));
        return responses;
    }

    /**
     * Treat each active subscription whose coverage period contains the date as a
     * synthetic full-day occupancy on its product. The frontend can then mark the
     * desk (or other subscribed product) as booked for every slot of the day.
     *
     * Synthetic responses use a negative ID derived from the subscription ID so
     * they don't collide with real bloqueo IDs and can be visually distinguished
     * if desired.
     */
    private List<PublicAvailabilityResponse> subscriptionResponses(
        LocalDate date,
        List<String> normalizedProductNames,
        List<String> normalizedCenters,
        LocalDateTime start,
        LocalDateTime end
    ) {
        List<Subscription> subs = subscriptionRepository.findActiveCoveringDate(date);
        LOGGER.info("subscriptionResponses date={} subs.size={} ids={}", date, subs.size(),
            subs.stream().map(s -> s.getId() + ":" + s.getProductoId()).toList());

        // Targeted probe: can we load sub 425 directly? If yes, the WHERE clause is dropping it.
        var probe = subscriptionRepository.findById(425);
        LOGGER.info("DEBUG findById(425) present={} {}",
            probe.isPresent(),
            probe.map(s -> "active=" + s.getActive() + " productoId=" + s.getProductoId()
                + " startDate=" + s.getStartDate() + " endDate=" + s.getEndDate()
                + " vatPercent=" + s.getVatPercent()).orElse("(not found)"));

        if (subs.isEmpty()) return Collections.emptyList();

        Set<Long> productoIds = subs.stream()
            .map(Subscription::getProductoId)
            .filter(java.util.Objects::nonNull)
            .collect(Collectors.toSet());
        if (productoIds.isEmpty()) return Collections.emptyList();

        Map<Long, Producto> productosById = productoRepository.findAllById(productoIds).stream()
            .collect(Collectors.toMap(Producto::getId, p -> p));

        boolean filterByName = !normalizedProductNames.isEmpty();
        boolean filterByCenter = !normalizedCenters.isEmpty();

        Set<Long> contactIds = subs.stream()
            .map(Subscription::getContactId)
            .filter(java.util.Objects::nonNull)
            .collect(Collectors.toSet());
        Map<Long, ContactProfile> contactsById = contactProfileRepository.findAllById(contactIds).stream()
            .collect(Collectors.toMap(ContactProfile::getId, c -> c));

        List<PublicAvailabilityResponse> out = new ArrayList<>();
        for (Subscription sub : subs) {
            Producto producto = productosById.get(sub.getProductoId());
            if (producto == null) {
                LOGGER.warn("subscriptionResponses: sub id={} skipped — no producto for productoId={}", sub.getId(), sub.getProductoId());
                continue;
            }

            if (filterByName) {
                String name = producto.getNombre();
                if (name == null) {
                    LOGGER.warn("subscriptionResponses: sub id={} skipped — producto {} has null name", sub.getId(), producto.getId());
                    continue;
                }
                if (!normalizedProductNames.contains(name.toLowerCase(Locale.ROOT))) {
                    LOGGER.warn("subscriptionResponses: sub id={} skipped — name '{}' not in {}", sub.getId(), name, normalizedProductNames);
                    continue;
                }
            }
            if (filterByCenter) {
                String centro = producto.getCentroCodigo();
                if (centro == null) {
                    LOGGER.warn("subscriptionResponses: sub id={} skipped — producto {} has null centro", sub.getId(), producto.getId());
                    continue;
                }
                if (!normalizedCenters.contains(centro.toLowerCase(Locale.ROOT))) {
                    LOGGER.warn("subscriptionResponses: sub id={} skipped — centro '{}' not in {}", sub.getId(), centro, normalizedCenters);
                    continue;
                }
            }

            ContactProfile cliente = sub.getContactId() != null ? contactsById.get(sub.getContactId()) : null;
            String clienteName = cliente != null ? resolveContactName(cliente) : "";

            out.add(new PublicAvailabilityResponse(
                -sub.getId().longValue(),
                "subscribed",
                start,
                end,
                cliente != null ? new PublicAvailabilityResponse.PublicCliente(cliente.getId(), clienteName) : null,
                new PublicAvailabilityResponse.PublicProducto(
                    producto.getId(),
                    producto.getNombre(),
                    producto.getCentroCodigo()
                )
            ));
        }
        return out;
    }

    private static String resolveContactName(ContactProfile profile) {
        if (profile.getName() != null && !profile.getName().isBlank()) return profile.getName();
        if (profile.getContactName() != null && !profile.getContactName().isBlank()) return profile.getContactName();
        return "";
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
