package com.beworking.bekey;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reconciles BeKey door grants against booked slots (#151). Diff-based: only the
 * changes hit Akiles, the unchanged middle is skipped.
 *
 *   desired = paid, current/upcoming bloqueos on door-mapped rooms (MA1A1..MA1A5)
 *   existing = active Source.booking grants, keyed by bloqueo id (sourceRef)
 *
 *   in desired, not granted  -> grantForBloqueo  (async invoice-paid bookings)
 *   granted, not in desired  -> revoke           (deleted / cancelled / unpaid / past)
 *
 * Covers #150's async-paid grants and per-slot revoke-on-cancel. Runs every 30 min;
 * the day-pinned window means a same-day invoice payment opens the door within ~30 min.
 */
@Component
public class BeKeyReconciliationScheduler {

    private static final Logger logger = LoggerFactory.getLogger(BeKeyReconciliationScheduler.class);
    private static final ZoneId ZONE = ZoneId.of("Europe/Madrid");

    private static final String DESIRED_SQL = """
        SELECT b.id AS bloqueo_id, b.id_cliente, p.nombre AS room_code,
               b.fecha_ini, b.fecha_fin, b.fin_indefinido
          FROM beworking.bloqueos b
          JOIN beworking.productos p ON p.id = b.id_producto
         WHERE b.estado = 'Pagado'
           AND b.id_cliente IS NOT NULL
           AND (b.fin_indefinido = 1 OR b.fecha_fin >= CURRENT_DATE)
           AND UPPER(p.nombre) IN ('MA1A1','MA1A2','MA1A3','MA1A4','MA1A5')
        """;

    private final JdbcTemplate jdbcTemplate;
    private final BeKeyAccessService beKeyAccessService;
    private final BeKeyAccessRepository accessRepository;

    public BeKeyReconciliationScheduler(JdbcTemplate jdbcTemplate,
                                        BeKeyAccessService beKeyAccessService,
                                        BeKeyAccessRepository accessRepository) {
        this.jdbcTemplate = jdbcTemplate;
        this.beKeyAccessService = beKeyAccessService;
        this.accessRepository = accessRepository;
    }

    // Every 30 minutes (UTC). Day-pinned windows make exact timing non-critical.
    @Scheduled(cron = "0 0,30 * * * *")
    public void reconcile() {
        runOnce();
    }

    public RunResult runOnce() {
        List<Map<String, Object>> desired = jdbcTemplate.queryForList(DESIRED_SQL);

        // Existing active booking grants, keyed by bloqueo id (sourceRef).
        Map<Long, BeKeyAccess> existing = new HashMap<>();
        for (BeKeyAccess g : accessRepository.findBySourceAndRevokedAtIsNull(BeKeyAccess.Source.booking)) {
            if (g.getSourceRef() != null) {
                existing.put(g.getSourceRef(), g);
            }
        }

        int granted = 0;
        int revoked = 0;
        Set<Long> desiredIds = new HashSet<>();

        // ADD — desired slots that have no active grant yet.
        for (Map<String, Object> row : desired) {
            Long bloqueoId = ((Number) row.get("bloqueo_id")).longValue();
            desiredIds.add(bloqueoId);
            if (existing.containsKey(bloqueoId)) {
                continue;   // already granted — no Akiles call
            }
            try {
                Long contactId = ((Number) row.get("id_cliente")).longValue();
                String roomCode = (String) row.get("room_code");
                Object finObj = row.get("fin_indefinido");
                boolean openEnded = finObj != null && ((Number) finObj).intValue() == 1;
                LocalDateTime fechaIni = ((Timestamp) row.get("fecha_ini")).toLocalDateTime();
                LocalDateTime fechaFin = ((Timestamp) row.get("fecha_fin")).toLocalDateTime();
                OffsetDateTime startsAt = fechaIni.toLocalDate().atStartOfDay(ZONE).toOffsetDateTime();
                OffsetDateTime expiresAt = openEnded
                        ? null
                        : fechaFin.toLocalDate().plusDays(1).atStartOfDay(ZONE).minusSeconds(1).toOffsetDateTime();
                beKeyAccessService.grantForBloqueo(contactId, bloqueoId, roomCode, startsAt, expiresAt);
                granted++;
            } catch (Exception ex) {
                logger.warn("BeKey reconcile: grant for bloqueo {} failed: {}", bloqueoId, ex.getMessage());
            }
        }

        // REMOVE — active grants whose slot is no longer desired.
        for (Map.Entry<Long, BeKeyAccess> e : existing.entrySet()) {
            if (desiredIds.contains(e.getKey())) {
                continue;
            }
            try {
                beKeyAccessService.revoke(e.getValue().getId(), "reconcile: booking slot no longer paid/active");
                revoked++;
            } catch (Exception ex) {
                logger.warn("BeKey reconcile: revoke of access {} (bloqueo {}) failed: {}",
                        e.getValue().getId(), e.getKey(), ex.getMessage());
            }
        }

        if (granted > 0 || revoked > 0) {
            logger.info("BeKey booking reconcile: granted={} revoked={} (desired slots={})",
                    granted, revoked, desiredIds.size());
        }
        return new RunResult(granted, revoked);
    }

    public record RunResult(int granted, int revoked) {}
}
