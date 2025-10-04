package com.beworking.bookings;

import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface ReservaRepository extends JpaRepository<Reserva, Long> {

    @Query(value = """
        SELECT r.*
        FROM beworking.reservas r
        WHERE (r.reserva_hasta IS NULL OR r.reserva_hasta >= CAST(:from AS date))
          AND (r.reserva_desde IS NULL OR r.reserva_desde <= CAST(:to AS date))
          AND (:tenantId IS NULL OR r.id_cliente = CAST(:tenantId AS bigint))
          AND (:centerId IS NULL OR r.id_centro = CAST(:centerId AS bigint))
        ORDER BY r.reserva_desde DESC, r.reserva_hora_desde ASC, r.id DESC
    """, nativeQuery = true)
    List<Reserva> findBookings(@Param("from") LocalDate from,
                               @Param("to") LocalDate to,
                               @Param("tenantId") Long tenantId,
                               @Param("centerId") Long centerId);
}
