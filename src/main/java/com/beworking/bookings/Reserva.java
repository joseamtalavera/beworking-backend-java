package com.beworking.bookings;

import com.beworking.contacts.ContactProfile;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "reservas", schema = "beworking")
public class Reserva {

    @Id
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "id_cliente")
    private ContactProfile cliente;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "id_centro")
    private Centro centro;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "id_producto")
    private Producto producto;

    @Column(name = "tipo_reserva")
    private String tipoReserva;

    @Column(name = "reserva_desde")
    private LocalDate reservaDesde;

    @Column(name = "reserva_hasta")
    private LocalDate reservaHasta;

    @Column(name = "fin_indefinido")
    private Integer finIndefinido;

    @Column(name = "lunes")
    private Integer lunes;

    @Column(name = "martes")
    private Integer martes;

    @Column(name = "miercoles")
    private Integer miercoles;

    @Column(name = "jueves")
    private Integer jueves;

    @Column(name = "viernes")
    private Integer viernes;

    @Column(name = "sabado")
    private Integer sabado;

    @Column(name = "domingo")
    private Integer domingo;

    @Column(name = "reserva_hora_desde")
    private String reservaHoraDesde;

    @Column(name = "reserva_hora_hasta")
    private String reservaHoraHasta;

    @Column(name = "tarifa")
    private Double tarifa;

    @Column(name = "asistentes")
    private Integer asistentes;

    @Column(name = "configuracion")
    private String configuracion;

    @Column(name = "notas")
    private String notas;

    @Column(name = "creacion_fecha")
    private LocalDateTime creacionFecha;

    @Column(name = "edicion_fecha")
    private LocalDateTime edicionFecha;

    @Column(name = "estado")
    private String estado;

    @Column(name = "usuario")
    private Integer usuarioId;

    @OneToMany(mappedBy = "reserva", fetch = FetchType.LAZY)
    private List<Bloqueo> bloqueos = new ArrayList<>();

    public Reserva() {
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
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

    public String getTipoReserva() {
        return tipoReserva;
    }

    public void setTipoReserva(String tipoReserva) {
        this.tipoReserva = tipoReserva;
    }

    public LocalDate getReservaDesde() {
        return reservaDesde;
    }

    public void setReservaDesde(LocalDate reservaDesde) {
        this.reservaDesde = reservaDesde;
    }

    public LocalDate getReservaHasta() {
        return reservaHasta;
    }

    public void setReservaHasta(LocalDate reservaHasta) {
        this.reservaHasta = reservaHasta;
    }

    public Integer getFinIndefinido() {
        return finIndefinido;
    }

    public void setFinIndefinido(Integer finIndefinido) {
        this.finIndefinido = finIndefinido;
    }

    public Integer getLunes() {
        return lunes;
    }

    public void setLunes(Integer lunes) {
        this.lunes = lunes;
    }

    public Integer getMartes() {
        return martes;
    }

    public void setMartes(Integer martes) {
        this.martes = martes;
    }

    public Integer getMiercoles() {
        return miercoles;
    }

    public void setMiercoles(Integer miercoles) {
        this.miercoles = miercoles;
    }

    public Integer getJueves() {
        return jueves;
    }

    public void setJueves(Integer jueves) {
        this.jueves = jueves;
    }

    public Integer getViernes() {
        return viernes;
    }

    public void setViernes(Integer viernes) {
        this.viernes = viernes;
    }

    public Integer getSabado() {
        return sabado;
    }

    public void setSabado(Integer sabado) {
        this.sabado = sabado;
    }

    public Integer getDomingo() {
        return domingo;
    }

    public void setDomingo(Integer domingo) {
        this.domingo = domingo;
    }

    public String getReservaHoraDesde() {
        return reservaHoraDesde;
    }

    public void setReservaHoraDesde(String reservaHoraDesde) {
        this.reservaHoraDesde = reservaHoraDesde;
    }

    public String getReservaHoraHasta() {
        return reservaHoraHasta;
    }

    public void setReservaHoraHasta(String reservaHoraHasta) {
        this.reservaHoraHasta = reservaHoraHasta;
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

    public String getNotas() {
        return notas;
    }

    public void setNotas(String notas) {
        this.notas = notas;
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

    public String getEstado() {
        return estado;
    }

    public void setEstado(String estado) {
        this.estado = estado;
    }

    public Integer getUsuarioId() {
        return usuarioId;
    }

    public void setUsuarioId(Integer usuarioId) {
        this.usuarioId = usuarioId;
    }

    public List<Bloqueo> getBloqueos() {
        return bloqueos;
    }

    public void setBloqueos(List<Bloqueo> bloqueos) {
        this.bloqueos = bloqueos;
    }
}
