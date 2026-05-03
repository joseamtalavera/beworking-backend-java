package com.beworking.tax;

import com.beworking.contacts.ViesVatService;
import java.time.LocalDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Thin wrapper over {@link ViesVatService} that:
 * <ol>
 *   <li>Always logs the call to {@code vat_validations} (audit trail for AEAT).</li>
 *   <li>Maps SOAP errors to a {@link Result#UNREACHABLE} state instead of FALSE.
 *       Callers must handle UNREACHABLE explicitly — the previous behaviour
 *       (silently storing FALSE on every transient error) was the root cause
 *       of the €15 ↔ €18.15 oscillation customers were complaining about.</li>
 * </ol>
 *
 * <p>This is the single place that calls {@link ViesVatService} for the resolver
 * pipeline. Other code paths (e.g. {@code GET /vat/validate} for live form
 * feedback) may still call ViesVatService directly without persisting.
 */
@Component
public class ViesGateway {

    private static final Logger logger = LoggerFactory.getLogger(ViesGateway.class);

    private final ViesVatService viesVatService;
    private final VatValidationLogRepository logRepository;

    public ViesGateway(ViesVatService viesVatService,
                       VatValidationLogRepository logRepository) {
        this.viesVatService = viesVatService;
        this.logRepository = logRepository;
    }

    public enum Result { VALID, INVALID, UNREACHABLE }

    public record GatewayResult(Result result, Long auditLogId, String consultationNumber) {
        public boolean isValid() { return result == Result.VALID; }
        public boolean isInvalid() { return result == Result.INVALID; }
        public boolean isUnreachable() { return result == Result.UNREACHABLE; }
    }

    /**
     * Validate against VIES, persist an audit row, and return the outcome.
     *
     * @param contactId the contact whose VAT is being checked (for the audit log)
     * @param taxIdValue the value as given (will be passed to VIES; the country
     *                   parameter is separate so VIES knows which DB to query)
     * @param countryIso ISO-2 country code (ES, FR, etc.) — required by VIES
     */
    public GatewayResult validate(Long contactId, String taxIdValue, String countryIso) {
        VatValidationLog log = new VatValidationLog();
        log.setContactId(contactId);
        log.setCheckedAt(LocalDateTime.now());
        log.setTaxId(taxIdValue != null ? taxIdValue : "");
        log.setCountry(countryIso != null ? countryIso : "");

        Result result;
        String consultation = null;
        try {
            ViesVatService.VatValidationResult viesResult = viesVatService.validate(taxIdValue, countryIso);
            result = viesResult.valid() ? Result.VALID : Result.INVALID;
            // ViesVatService doesn't currently surface the consultation number.
            // Once it does, plumb it through here for full AEAT defensibility.
        } catch (Exception e) {
            logger.warn("VIES unreachable for contact {} (taxId={}, country={}): {}",
                contactId, taxIdValue, countryIso, e.getMessage());
            result = Result.UNREACHABLE;
        }

        log.setViesResult(result.name().toLowerCase());
        log.setConsultationNumber(consultation);
        try {
            logRepository.save(log);
        } catch (Exception persistError) {
            // Audit-log write failure must not block the validation flow.
            logger.error("Failed to persist VIES audit row for contact {}: {}",
                contactId, persistError.getMessage());
        }
        return new GatewayResult(result, log.getId(), consultation);
    }
}
