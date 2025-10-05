package com.beworking.invoices;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "facturas", schema = "beworking")
public class InvoiceRow {
    @Id
    private Long id;
}


