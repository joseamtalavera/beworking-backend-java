package com.beworking.tax;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/**
 * Append-only audit row for every VIES call. Read by AEAT inspections to prove
 * that a reverse-charge invoice's VIES status was confirmed at a specific
 * timestamp with a specific consultation number. Lives in the V47-created
 * {@code beworking.vat_validations} table.
 */
@Entity
@Table(name = "vat_validations", schema = "beworking")
public class VatValidationLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "contact_id", nullable = false)
    private Long contactId;

    @Column(name = "checked_at", nullable = false)
    private LocalDateTime checkedAt;

    @Column(name = "tax_id", nullable = false)
    private String taxId;

    @Column(name = "country", nullable = false)
    private String country;

    @Column(name = "vies_result", nullable = false)
    private String viesResult;  // 'valid' | 'invalid' | 'unreachable'

    @Column(name = "consultation_number")
    private String consultationNumber;

    @Column(name = "request_payload")
    private String requestPayload;

    @Column(name = "response_payload")
    private String responsePayload;

    public VatValidationLog() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getContactId() { return contactId; }
    public void setContactId(Long contactId) { this.contactId = contactId; }
    public LocalDateTime getCheckedAt() { return checkedAt; }
    public void setCheckedAt(LocalDateTime checkedAt) { this.checkedAt = checkedAt; }
    public String getTaxId() { return taxId; }
    public void setTaxId(String taxId) { this.taxId = taxId; }
    public String getCountry() { return country; }
    public void setCountry(String country) { this.country = country; }
    public String getViesResult() { return viesResult; }
    public void setViesResult(String viesResult) { this.viesResult = viesResult; }
    public String getConsultationNumber() { return consultationNumber; }
    public void setConsultationNumber(String consultationNumber) { this.consultationNumber = consultationNumber; }
    public String getRequestPayload() { return requestPayload; }
    public void setRequestPayload(String requestPayload) { this.requestPayload = requestPayload; }
    public String getResponsePayload() { return responsePayload; }
    public void setResponsePayload(String responsePayload) { this.responsePayload = responsePayload; }
}
