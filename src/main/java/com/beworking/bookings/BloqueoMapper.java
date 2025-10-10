package com.beworking.bookings;

import com.beworking.bookings.BloqueoResponse.CentroSummary;
import com.beworking.bookings.BloqueoResponse.ClienteSummary;
import com.beworking.bookings.BloqueoResponse.ProductoSummary;
import com.beworking.contacts.ContactProfile;

final class BloqueoMapper {

    private BloqueoMapper() {
    }

    static BloqueoResponse toResponse(Bloqueo bloqueo) {
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
