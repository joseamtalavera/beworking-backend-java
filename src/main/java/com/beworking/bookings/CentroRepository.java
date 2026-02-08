package com.beworking.bookings;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface CentroRepository extends JpaRepository<Centro, Long> {

    Optional<Centro> findByCodigoIgnoreCase(String codigo);
}
