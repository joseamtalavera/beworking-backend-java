package com.beworking.invoices;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Surfaces past-due meeting-room facturas (cuenta=PT, tenant_type='Usuario Aulas').
 *
 * Past-due definition: Pendiente status + creacionfecha older than 24h.
 * Mirrors the Subscription-side reconciliation pattern but for one-off
 * meeting-room invoices, which don't have a Stripe sub backing them.
 *
 * Used by the dashboard reconciliation card (Meeting Rooms tab) and the
 * daily MeetingRoomReconciliationScheduler email to info@.
 */
@Service
public class MeetingRoomReconciliationService {

    private static final Logger logger = LoggerFactory.getLogger(MeetingRoomReconciliationService.class);

    private final JdbcTemplate jdbcTemplate;

    public MeetingRoomReconciliationService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<PastDueRoomInvoice> findPastDue() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
            SELECT f.id, f.idfactura, f.total, f.creacionfecha, f.estado,
                   f.stripeinvoiceid,
                   cp.id AS contact_id, cp.name, cp.email_primary, cp.phone_primary,
                   EXTRACT(EPOCH FROM (NOW() - f.creacionfecha)) / 86400 AS days_past_due
              FROM beworking.facturas f
              JOIN beworking.contact_profiles cp ON cp.id = f.idcliente
             WHERE UPPER(COALESCE(NULLIF(f.holdedcuenta, ''), 'PT')) = 'PT'
               AND cp.tenant_type = 'Usuario Aulas'
               AND f.creacionfecha < NOW() - INTERVAL '1 day'
               AND f.idfactura < 100000
               AND (LOWER(COALESCE(f.estado, '')) LIKE '%pend%'
                 OR LOWER(COALESCE(f.estado, '')) LIKE '%confir%'
                 OR LOWER(COALESCE(f.estado, '')) LIKE '%fact%'
                 OR LOWER(COALESCE(f.estado, '')) LIKE '%invoice%'
                 OR LOWER(COALESCE(f.estado, '')) LIKE '%created%')
             ORDER BY f.creacionfecha ASC
            """);

        List<PastDueRoomInvoice> out = new ArrayList<>(rows.size());
        for (Map<String, Object> r : rows) {
            Number daysObj = (Number) r.get("days_past_due");
            int days = daysObj != null ? (int) Math.floor(daysObj.doubleValue()) : 0;
            out.add(new PastDueRoomInvoice(
                ((Number) r.get("id")).longValue(),
                r.get("idfactura") != null ? ((Number) r.get("idfactura")).intValue() : null,
                r.get("contact_id") != null ? ((Number) r.get("contact_id")).longValue() : null,
                (String) r.get("name"),
                (String) r.get("email_primary"),
                (String) r.get("phone_primary"),
                r.get("total") != null ? new BigDecimal(r.get("total").toString()) : BigDecimal.ZERO,
                r.get("creacionfecha") instanceof java.sql.Timestamp ts ? ts.toLocalDateTime() : null,
                (String) r.get("estado"),
                (String) r.get("stripeinvoiceid"),
                days
            ));
        }
        if (!out.isEmpty()) {
            logger.info("Meeting-room past-due check: {} invoices", out.size());
        }
        return out;
    }

    public record PastDueRoomInvoice(
        Long facturaId,
        Integer idfactura,
        Long contactId,
        String customerName,
        String customerEmail,
        String customerPhone,
        BigDecimal total,
        LocalDateTime creacionfecha,
        String estado,
        String stripeInvoiceId,
        int daysPastDue
    ) {}
}
