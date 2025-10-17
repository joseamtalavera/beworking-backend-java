package com.beworking.cuentas;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CuentaRepository extends JpaRepository<Cuenta, Integer> {
    
    /**
     * Find all active cuentas
     */
    List<Cuenta> findByActivoTrueOrderByNombre();
    
    /**
     * Find cuenta by codigo
     */
    Optional<Cuenta> findByCodigo(String codigo);
    
    /**
     * Get the next sequential number for a specific cuenta
     */
    @Query("SELECT c.numeroSecuencial FROM Cuenta c WHERE c.id = :cuentaId")
    Optional<Integer> getNextSequentialNumber(@Param("cuentaId") Integer cuentaId);
    
    /**
     * Update the sequential number for a cuenta (used after generating invoice number)
     */
    @Query("UPDATE Cuenta c SET c.numeroSecuencial = :newNumber WHERE c.id = :cuentaId")
    void updateSequentialNumber(@Param("cuentaId") Integer cuentaId, @Param("newNumber") Integer newNumber);
}
