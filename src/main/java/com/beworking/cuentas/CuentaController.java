package com.beworking.cuentas;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/cuentas")
@CrossOrigin(origins = "*")
public class CuentaController {
    
    @Autowired
    private CuentaService cuentaService;
    
    /**
     * Get all active cuentas
     */
    @GetMapping
    public ResponseEntity<List<Cuenta>> getAllActiveCuentas() {
        try {
            List<Cuenta> cuentas = cuentaService.getAllActiveCuentas();
            return ResponseEntity.ok(cuentas);
        } catch (Exception e) {
            return ResponseEntity.status(500).build();
        }
    }
    
    /**
     * Get cuenta by ID
     */
    @GetMapping("/{id}")
    public ResponseEntity<Cuenta> getCuentaById(@PathVariable Integer id) {
        try {
            Optional<Cuenta> cuenta = cuentaService.getCuentaById(id);
            return cuenta.map(ResponseEntity::ok)
                        .orElse(ResponseEntity.notFound().build());
        } catch (Exception e) {
            return ResponseEntity.status(500).build();
        }
    }
    
    /**
     * Get next invoice number for a specific cuenta
     */
    @GetMapping("/{id}/next-invoice-number")
    public ResponseEntity<Map<String, Object>> getNextInvoiceNumber(@PathVariable Integer id) {
        try {
            String nextNumber = cuentaService.generateNextInvoiceNumber(id);
            Map<String, Object> response = new HashMap<>();
            response.put("nextNumber", nextNumber);
            response.put("cuentaId", id);
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            Map<String, Object> error = new HashMap<>();
            error.put("error", e.getMessage());
            return ResponseEntity.badRequest().body(error);
        } catch (Exception e) {
            Map<String, Object> error = new HashMap<>();
            error.put("error", "Failed to generate next invoice number");
            return ResponseEntity.status(500).body(error);
        }
    }
    
    /**
     * Get next invoice number by cuenta codigo
     */
    @GetMapping("/codigo/{codigo}/next-invoice-number")
    public ResponseEntity<Map<String, Object>> getNextInvoiceNumberByCodigo(@PathVariable String codigo) {
        try {
            String nextNumber = cuentaService.generateNextInvoiceNumber(codigo);
            Map<String, Object> response = new HashMap<>();
            response.put("nextNumber", nextNumber);
            response.put("codigo", codigo);
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            Map<String, Object> error = new HashMap<>();
            error.put("error", e.getMessage());
            return ResponseEntity.badRequest().body(error);
        } catch (Exception e) {
            Map<String, Object> error = new HashMap<>();
            error.put("error", "Failed to generate next invoice number");
            return ResponseEntity.status(500).body(error);
        }
    }
    
    /**
     * Create a new cuenta
     */
    @PostMapping
    public ResponseEntity<Cuenta> createCuenta(@RequestBody Cuenta cuenta) {
        try {
            Cuenta createdCuenta = cuentaService.createCuenta(cuenta);
            return ResponseEntity.ok(createdCuenta);
        } catch (Exception e) {
            return ResponseEntity.status(500).build();
        }
    }
    
    /**
     * Update an existing cuenta
     */
    @PutMapping("/{id}")
    public ResponseEntity<Cuenta> updateCuenta(@PathVariable Integer id, @RequestBody Cuenta cuenta) {
        try {
            cuenta.setId(id);
            Cuenta updatedCuenta = cuentaService.updateCuenta(cuenta);
            return ResponseEntity.ok(updatedCuenta);
        } catch (Exception e) {
            return ResponseEntity.status(500).build();
        }
    }
    
    /**
     * Deactivate a cuenta
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, String>> deactivateCuenta(@PathVariable Integer id) {
        try {
            cuentaService.deactivateCuenta(id);
            Map<String, String> response = new HashMap<>();
            response.put("message", "Cuenta deactivated successfully");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            Map<String, String> error = new HashMap<>();
            error.put("error", "Failed to deactivate cuenta");
            return ResponseEntity.status(500).body(error);
        }
    }
}
