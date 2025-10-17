package com.beworking.cuentas;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "cuentas", schema = "beworking")
public class Cuenta {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;
    
    @Column(name = "codigo", nullable = false, unique = true, length = 10)
    private String codigo;
    
    @Column(name = "nombre", nullable = false, length = 100)
    private String nombre;
    
    @Column(name = "descripcion", columnDefinition = "TEXT")
    private String descripcion;
    
    @Column(name = "activo", nullable = false)
    private Boolean activo = true;
    
    @Column(name = "prefijo_factura", length = 5)
    private String prefijoFactura = "F";
    
    @Column(name = "numero_secuencial", nullable = false)
    private Integer numeroSecuencial = 0;
    
    @Column(name = "created_at")
    private LocalDateTime createdAt;
    
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
    
    // Constructors
    public Cuenta() {}
    
    public Cuenta(String codigo, String nombre, String descripcion) {
        this.codigo = codigo;
        this.nombre = nombre;
        this.descripcion = descripcion;
    }
    
    // Getters and Setters
    public Integer getId() {
        return id;
    }
    
    public void setId(Integer id) {
        this.id = id;
    }
    
    public String getCodigo() {
        return codigo;
    }
    
    public void setCodigo(String codigo) {
        this.codigo = codigo;
    }
    
    public String getNombre() {
        return nombre;
    }
    
    public void setNombre(String nombre) {
        this.nombre = nombre;
    }
    
    public String getDescripcion() {
        return descripcion;
    }
    
    public void setDescripcion(String descripcion) {
        this.descripcion = descripcion;
    }
    
    public Boolean getActivo() {
        return activo;
    }
    
    public void setActivo(Boolean activo) {
        this.activo = activo;
    }
    
    public String getPrefijoFactura() {
        return prefijoFactura;
    }
    
    public void setPrefijoFactura(String prefijoFactura) {
        this.prefijoFactura = prefijoFactura;
    }
    
    public Integer getNumeroSecuencial() {
        return numeroSecuencial;
    }
    
    public void setNumeroSecuencial(Integer numeroSecuencial) {
        this.numeroSecuencial = numeroSecuencial;
    }
    
    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
    
    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
    
    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
    
    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
    
    // Helper method to generate next invoice number
    public String generateNextInvoiceNumber() {
        this.numeroSecuencial++;
        return this.prefijoFactura + String.format("%03d", this.numeroSecuencial);
    }
}
