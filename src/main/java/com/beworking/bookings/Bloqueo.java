package com.beworking.bookings;

import com.beworking.contacts.ContactProfile;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

@Entity
@Table(name = "bloqueos", schema = "beworking")
public class Bloqueo {

    @Id
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "id_reserva")
    private Reserva reserva;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "id_cliente")
    private ContactProfile cliente;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "id_centro")
    private Centro centro;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "id_producto")
    private Producto producto;

    @Column(name = "fecha_ini")
    private LocalDateTime fechaIni;

    @Column(name = "fecha_fin")
    private LocalDateTime fechaFin;

    @Column(name = "fin_indefinido")
    private Integer finIndefinido;

    @Column(name = "tarifa")
    private Double tarifa;

    @Column(name = "asistentes")
    private Integer asistentes;

    @Column(name = "configuracion")
    private String configuracion;

    @Column(name = "nota")
    private String nota;

    @Column(name = "estado")
    private String estado;

    @Column(name = "creacion_fecha")
    private LocalDateTime creacionFecha;

    @Column(name = "edicion_fecha")
    private LocalDateTime edicionFecha;

    public Bloqueo() {
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Reserva getReserva() {
        return reserva;
    }

    public void setReserva(Reserva reserva) {
        this.reserva = reserva;
    }

    public ContactProfile getCliente() {
        return cliente;
    }

    public void setCliente(ContactProfile cliente) {
        this.cliente = cliente;
    }

    public Centro getCentro() {
        return centro;
    }

    public void setCentro(Centro centro) {
        this.centro = centro;
    }

    public Producto getProducto() {
        return producto;
    }

    public void setProducto(Producto producto) {
        this.producto = producto;
    }

    public LocalDateTime getFechaIni() {
        return fechaIni;
    }

    public void setFechaIni(LocalDateTime fechaIni) {
        this.fechaIni = fechaIni;
    }

    public LocalDateTime getFechaFin() {
        return fechaFin;
    }

    public void setFechaFin(LocalDateTime fechaFin) {
        this.fechaFin = fechaFin;
    }

    public Integer getFinIndefinido() {
        return finIndefinido;
    }

    public void setFinIndefinido(Integer finIndefinido) {
        this.finIndefinido = finIndefinido;
    }

    public Double getTarifa() {
        return tarifa;
    }

    public void setTarifa(Double tarifa) {
        this.tarifa = tarifa;
    }

    public Integer getAsistentes() {
        return asistentes;
    }

    public void setAsistentes(Integer asistentes) {
        this.asistentes = asistentes;
    }

    public String getConfiguracion() {
        return configuracion;
    }

    public void setConfiguracion(String configuracion) {
        this.configuracion = configuracion;
    }

    public String getNota() {
        return nota;
    }

    public void setNota(String nota) {
        this.nota = nota;
    }

    public String getEstado() {
        return estado;
    }

    public void setEstado(String estado) {
        this.estado = estado;
    }

    public LocalDateTime getCreacionFecha() {
        return creacionFecha;
    }

    public void setCreacionFecha(LocalDateTime creacionFecha) {
        this.creacionFecha = creacionFecha;
    }

    public LocalDateTime getEdicionFecha() {
        return edicionFecha;
    }

    public void setEdicionFecha(LocalDateTime edicionFecha) {
        this.edicionFecha = edicionFecha;
    }
}
