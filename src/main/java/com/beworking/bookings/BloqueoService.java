package com.beworking.bookings;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class BloqueoService {

    private static final Logger LOGGER = LoggerFactory.getLogger(BloqueoService.class);

    private final BloqueoRepository bloqueoRepository;

    BloqueoService(BloqueoRepository bloqueoRepository) {
        this.bloqueoRepository = bloqueoRepository;
    }

    @Transactional(readOnly = true)
    List<BloqueoResponse> getBloqueos(LocalDate from,
                                      LocalDate to,
                                      Long centerId,
                                      Long contactId,
                                      Long productId,
                                      Long tenantId) {
        LocalDateTime rangeStart = from != null ? from.atStartOfDay() : null;
        LocalDateTime rangeEndExclusive = to != null ? to.plusDays(1).atStartOfDay() : null;

        boolean applyFrom = rangeStart != null;
        boolean applyTo = rangeEndExclusive != null;

        try {
            List<Bloqueo> bloqueos = bloqueoRepository.findBloqueos(
                rangeStart,
                rangeEndExclusive,
                centerId,
                contactId,
                productId,
                tenantId,
                applyFrom,
                applyTo
            );
            return bloqueos.stream()
                .map(BloqueoMapper::toResponse)
                .toList();
        } catch (DataAccessException ex) {
            LOGGER.warn("Failed to load bloqueos", ex);
            return List.of();
        }
    }

    @Transactional
    void deleteBloqueo(Long id) {
        try {
            bloqueoRepository.deleteById(id);
        } catch (EmptyResultDataAccessException ex) {
            LOGGER.warn("Attempted to delete non-existing bloqueo {}", id);
        }
    }
}
