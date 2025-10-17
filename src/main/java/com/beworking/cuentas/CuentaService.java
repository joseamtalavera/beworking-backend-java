package com.beworking.cuentas;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
@Transactional
public class CuentaService {
    
    @Autowired
    private CuentaRepository cuentaRepository;
    
    /**
     * Get all active cuentas
     */
    public List<Cuenta> getAllActiveCuentas() {
        return cuentaRepository.findByActivoTrueOrderByNombre();
    }
    
    /**
     * Get cuenta by ID
     */
    public Optional<Cuenta> getCuentaById(Integer id) {
        return cuentaRepository.findById(id);
    }
    
    /**
     * Get cuenta by codigo
     */
    public Optional<Cuenta> getCuentaByCodigo(String codigo) {
        return cuentaRepository.findByCodigo(codigo);
    }
    
    /**
     * Generate next invoice number for a specific cuenta
     * This method is thread-safe and updates the sequential number atomically
     */
    @Transactional
    public synchronized String generateNextInvoiceNumber(Integer cuentaId) {
        Optional<Cuenta> cuentaOpt = cuentaRepository.findById(cuentaId);
        if (cuentaOpt.isEmpty()) {
            throw new IllegalArgumentException("Cuenta not found with ID: " + cuentaId);
        }
        
        Cuenta cuenta = cuentaOpt.get();
        if (!cuenta.getActivo()) {
            throw new IllegalArgumentException("Cuenta is not active: " + cuenta.getCodigo());
        }
        
        // Increment the sequential number
        cuenta.setNumeroSecuencial(cuenta.getNumeroSecuencial() + 1);
        cuentaRepository.save(cuenta);
        
        // Generate the invoice number
        return cuenta.getPrefijoFactura() + String.format("%03d", cuenta.getNumeroSecuencial());
    }
    
    /**
     * Generate next invoice number by cuenta codigo
     */
    public String generateNextInvoiceNumber(String codigo) {
        Optional<Cuenta> cuentaOpt = cuentaRepository.findByCodigo(codigo);
        if (cuentaOpt.isEmpty()) {
            throw new IllegalArgumentException("Cuenta not found with codigo: " + codigo);
        }
        
        return generateNextInvoiceNumber(cuentaOpt.get().getId());
    }
    
    /**
     * Create a new cuenta
     */
    public Cuenta createCuenta(Cuenta cuenta) {
        return cuentaRepository.save(cuenta);
    }
    
    /**
     * Update an existing cuenta
     */
    public Cuenta updateCuenta(Cuenta cuenta) {
        return cuentaRepository.save(cuenta);
    }
    
    /**
     * Deactivate a cuenta (soft delete)
     */
    public void deactivateCuenta(Integer id) {
        Optional<Cuenta> cuentaOpt = cuentaRepository.findById(id);
        if (cuentaOpt.isPresent()) {
            Cuenta cuenta = cuentaOpt.get();
            cuenta.setActivo(false);
            cuentaRepository.save(cuenta);
        }
    }
}
