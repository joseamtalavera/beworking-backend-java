package com.beworking.bookings;

import java.time.LocalDateTime;

public record PublicAvailabilityResponse(
    Long id,
    String estado,
    LocalDateTime fechaIni,
    LocalDateTime fechaFin,
    PublicCliente cliente,
    PublicProducto producto
) {

    public static PublicAvailabilityResponse from(Bloqueo bloqueo) {
        return new PublicAvailabilityResponse(
            bloqueo.getId(),
            bloqueo.getEstado(),
            bloqueo.getFechaIni(),
            bloqueo.getFechaFin(),
            bloqueo.getCliente() != null
                ? new PublicCliente(
                    bloqueo.getCliente().getId(),
                    resolveClientName(bloqueo.getCliente())
                )
                : null,
            bloqueo.getProducto() != null
                ? new PublicProducto(
                    bloqueo.getProducto().getId(),
                    bloqueo.getProducto().getNombre(),
                    bloqueo.getProducto().getCentroCodigo()
                )
                : null
        );
    }

    private static String resolveClientName(com.beworking.contacts.ContactProfile profile) {
        if (profile.getName() != null && !profile.getName().isBlank()) {
            return profile.getName();
        }
        if (profile.getContactName() != null && !profile.getContactName().isBlank()) {
            return profile.getContactName();
        }
        return "";
    }

    public record PublicCliente(Long id, String nombre) {}

    public record PublicProducto(Long id, String nombre, String centroCodigo) {}
}
