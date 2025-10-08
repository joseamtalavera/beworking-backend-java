package com.beworking.bookings;

import java.time.LocalDateTime;

public record BloqueoResponse(
    Long id,
    LocalDateTime fechaIni,
    LocalDateTime fechaFin,
    boolean finIndefinido,
    Double tarifa,
    Integer asistentes,
    String configuracion,
    String nota,
    String estado,
    LocalDateTime creacionFecha,
    LocalDateTime edicionFecha,
    Long reservaId,
    CentroSummary centro,
    ProductoSummary producto,
    ClienteSummary cliente
) {

    public record CentroSummary(Long id, String nombre, String codigo) { }

    public record ProductoSummary(Long id, String nombre, String tipo, String centroCodigo) { }

    public record ClienteSummary(Long id, String nombre, String email, String tipoTenant) { }
}

