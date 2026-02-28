package com.beworking.bookings;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface BloqueoRepository extends JpaRepository<Bloqueo, Long> {
    @Query("select max(b.id) from Bloqueo b")
    Optional<Long> findMaxId();

    @Query("""
        SELECT DISTINCT b
        FROM Bloqueo b
        LEFT JOIN FETCH b.cliente c
        LEFT JOIN FETCH b.centro centro
        LEFT JOIN FETCH b.producto producto
        LEFT JOIN FETCH b.reserva reserva
        LEFT JOIN FETCH reserva.centro reservaCentro
        LEFT JOIN FETCH reserva.producto reservaProducto
        WHERE (:applyFrom = false OR b.fechaFin IS NULL OR b.fechaFin >= :from)
          AND (:applyTo = false OR b.fechaIni IS NULL OR b.fechaIni < :to)
          AND (:centerId IS NULL OR (centro IS NOT NULL AND centro.id = :centerId))
          AND (:contactId IS NULL OR (c IS NOT NULL AND c.id = :contactId))
          AND (:productId IS NULL OR (producto IS NOT NULL AND producto.id = :productId))
          AND (:tenantId IS NULL OR (c IS NOT NULL AND c.id = :tenantId))
    """)
    List<Bloqueo> findBloqueos(@Param("from") LocalDateTime from,
                               @Param("to") LocalDateTime to,
                               @Param("centerId") Long centerId,
                               @Param("contactId") Long contactId,
                               @Param("productId") Long productId,
                               @Param("tenantId") Long tenantId,
                               @Param("applyFrom") boolean applyFrom,
                               @Param("applyTo") boolean applyTo);

    @Query("""
        SELECT b
        FROM Bloqueo b
        WHERE b.producto.id = :productId
          AND b.fechaFin > :start
          AND b.fechaIni < :end
    """)
    List<Bloqueo> findOverlapping(@Param("productId") Long productId,
                                  @Param("start") LocalDateTime start,
                                  @Param("end") LocalDateTime end);

    @Query("""
        SELECT DISTINCT b
        FROM Bloqueo b
        LEFT JOIN FETCH b.cliente c
        LEFT JOIN FETCH b.centro centro
        LEFT JOIN FETCH b.producto producto
        WHERE (:productNamesEmpty = true OR LOWER(producto.nombre) IN :productNames)
          AND (:centerCodesEmpty = true OR LOWER(producto.centroCodigo) IN :centerCodes)
          AND b.fechaIni < :end
          AND (b.fechaFin IS NULL OR b.fechaFin > :start)
    """)
    List<Bloqueo> findPublicAvailability(@Param("productNames") List<String> productNames,
                                         @Param("productNamesEmpty") boolean productNamesEmpty,
                                         @Param("centerCodes") List<String> centerCodes,
                                         @Param("centerCodesEmpty") boolean centerCodesEmpty,
                                         @Param("start") LocalDateTime start,
                                         @Param("end") LocalDateTime end);

    @Query("""
        SELECT DISTINCT b
        FROM Bloqueo b
        LEFT JOIN FETCH b.cliente c
        LEFT JOIN FETCH b.centro centro
        LEFT JOIN FETCH b.producto producto
        WHERE c.id = :contactId
          AND b.fechaIni >= :fromDate
          AND (
              b.estado IS NULL
              OR (
                  LOWER(b.estado) NOT LIKE '%invoice%'
                  AND LOWER(b.estado) NOT LIKE '%factura%'
                  AND LOWER(b.estado) NOT LIKE '%pend%'
                  AND LOWER(b.estado) NOT LIKE '%pag%'
              )
          )
        ORDER BY b.fechaIni ASC
    """)
    List<Bloqueo> findUninvoicedByContact(@Param("contactId") Long contactId,
                                           @Param("fromDate") LocalDateTime fromDate);

    @Query("""
        SELECT DISTINCT b
        FROM Bloqueo b
        LEFT JOIN FETCH b.cliente c
        LEFT JOIN FETCH b.centro centro
        LEFT JOIN FETCH b.producto producto
        WHERE b.fechaIni >= :monthStart
          AND b.fechaIni < :monthEnd
          AND c.tenantType = 'Usuario Aulas'
          AND (
              b.estado IS NULL
              OR (
                  LOWER(b.estado) NOT LIKE '%invoice%'
                  AND LOWER(b.estado) NOT LIKE '%factura%'
                  AND LOWER(b.estado) NOT LIKE '%pend%'
                  AND LOWER(b.estado) NOT LIKE '%pag%'
              )
          )
        ORDER BY b.fechaIni ASC
    """)
    List<Bloqueo> findUninvoicedForMonth(@Param("monthStart") LocalDateTime monthStart,
                                          @Param("monthEnd") LocalDateTime monthEnd);
}
