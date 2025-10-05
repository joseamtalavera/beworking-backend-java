package com.beworking.invoices;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

@Repository
public interface InvoiceRepository extends JpaRepository<InvoiceRow, Long> {

    @Query(value = "" +
        "SELECT i.id, i.idfactura, i.idcliente, i.idcentro, i.descripcion, i.total, i.iva, i.totaliva, i.estado, i.creacionfecha AS created_at, i.holdedinvoicenum, i.holdedinvoicepdf " +
        "FROM beworking.facturas i " +
        "/* no tenant filter yet; can be added later */",
        countQuery = "SELECT COUNT(*) FROM beworking.facturas",
        nativeQuery = true)
    Page<InvoiceProjection> findInvoices(Pageable pageable);
}


