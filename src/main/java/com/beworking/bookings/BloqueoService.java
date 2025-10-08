package com.beworking.bookings;

import com.beworking.bookings.BloqueoResponse.CentroSummary;
import com.beworking.bookings.BloqueoResponse.ClienteSummary;
import com.beworking.bookings.BloqueoResponse.ProductoSummary;
import com.beworking.contacts.ContactProfile;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class BloqueoService {

    private static final Logger LOGGER = LoggerFactory.getLogger(BloqueoService.class);

    private final BloqueoRepository bloqueoRepository;

    BloqueoService(BloqueoRepository bloqueoRepository) {
        this.bloqueoRepository = bloqueoRepository;
    }

    @Transactional(readOnly = true)
    List<BloqueoResponse> getBloqueos(LocalDate from,
                                      LocalDate to,
                                      Long centerId,
                                      Long contactId,
                                      Long productId,
                                      Long tenantId) {
        LocalDateTime rangeStart = from != null ? from.atStartOfDay() : null;
        LocalDateTime rangeEndExclusive = to != null ? to.plusDays(1).atStartOfDay() : null;

        boolean applyFrom = rangeStart != null;
        boolean applyTo = rangeEndExclusive != null;

        try {
            List<Bloqueo> bloqueos = bloqueoRepository.findBloqueos(
                rangeStart,
                rangeEndExclusive,
                centerId,
                contactId,
                productId,
                tenantId,
                applyFrom,
                applyTo
            );
            return bloqueos.stream()
                .map(BloqueoService::mapToResponse)
                .toList();
        } catch (DataAccessException ex) {
            LOGGER.warn("Failed to load bloqueos", ex);
            return List.of();
        }
    }

    private static BloqueoResponse mapToResponse(Bloqueo bloqueo) {
        Centro centro = bloqueo.getCentro();
        Producto producto = bloqueo.getProducto();
        ContactProfile cliente = bloqueo.getCliente();

        return new BloqueoResponse(
            bloqueo.getId(),
            bloqueo.getFechaIni(),
            bloqueo.getFechaFin(),
            isTrue(bloqueo.getFinIndefinido()),
            bloqueo.getTarifa(),
            bloqueo.getAsistentes(),
            bloqueo.getConfiguracion(),
            bloqueo.getNota(),
            bloqueo.getEstado(),
            bloqueo.getCreacionFecha(),
            bloqueo.getEdicionFecha(),
            bloqueo.getReserva() != null ? bloqueo.getReserva().getId() : null,
            centro != null ? new CentroSummary(centro.getId(), centro.getNombre(), centro.getCodigo()) : null,
            producto != null
                ? new ProductoSummary(producto.getId(), producto.getNombre(), producto.getTipo(), producto.getCentroCodigo())
                : null,
            cliente != null
                ? new ClienteSummary(cliente.getId(), cliente.getName(), cliente.getEmailPrimary(), cliente.getTenantType())
                : null
        );
    }

    private static boolean isTrue(Integer value) {
        return value != null && value.intValue() == 1;
    }
}

