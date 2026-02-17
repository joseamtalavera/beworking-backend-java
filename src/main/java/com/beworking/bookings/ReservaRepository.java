package com.beworking.bookings;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface ReservaRepository extends JpaRepository<Reserva, Long> {
    @Query("select max(r.id) from Reserva r")
    Optional<Long> findMaxId();

    @Query("""
        SELECT DISTINCT r
        FROM Reserva r
        LEFT JOIN FETCH r.cliente c
        LEFT JOIN FETCH r.centro cen
        LEFT JOIN FETCH r.producto prod
        LEFT JOIN FETCH r.bloqueos b
        LEFT JOIN FETCH b.centro bloqueCentro
        LEFT JOIN FETCH b.producto bloqueProducto
        WHERE (:applyFrom = false OR r.reservaHasta IS NULL OR r.reservaHasta >= :from)
          AND (:applyTo = false OR r.reservaDesde IS NULL OR r.reservaDesde <= :to)
          AND (:tenantId IS NULL OR (c IS NOT NULL AND c.id = :tenantId))
          AND (:centerId IS NULL OR (cen IS NOT NULL AND cen.id = :centerId))
    """)
    List<Reserva> findBookings(@Param("from") LocalDate from,
                               @Param("to") LocalDate to,
                               @Param("tenantId") Long tenantId,
                               @Param("centerId") Long centerId,
                               @Param("applyFrom") boolean applyFrom,
                               @Param("applyTo") boolean applyTo);

    @Query("""
        SELECT COUNT(r)
        FROM Reserva r
        WHERE r.cliente.id = :contactId
          AND r.producto.id = :productoId
          AND r.creacionFecha >= :monthStart
          AND r.creacionFecha < :monthEnd
    """)
    long countByContactAndProductInMonth(@Param("contactId") Long contactId,
                                         @Param("productoId") Long productoId,
                                         @Param("monthStart") LocalDateTime monthStart,
                                         @Param("monthEnd") LocalDateTime monthEnd);

    @Query("""
        SELECT COUNT(r)
        FROM Reserva r
        WHERE r.cliente.id = :contactId
          AND r.creacionFecha >= :monthStart
          AND r.creacionFecha < :monthEnd
    """)
    long countByContactInMonth(@Param("contactId") Long contactId,
                               @Param("monthStart") LocalDateTime monthStart,
                               @Param("monthEnd") LocalDateTime monthEnd);
}
