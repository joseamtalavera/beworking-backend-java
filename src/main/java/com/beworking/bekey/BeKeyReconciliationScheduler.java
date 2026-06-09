package com.beworking.bekey;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
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
         WHERE b.estado IN ('Pagado', 'Free')
           AND b.id_cliente IS NOT NULL
           AND (b.fin_indefinido = 1 OR b.fecha_fin >= CURRENT_DATE)
           AND UPPER(p.nombre) IN ('MA1A1','MA1A2','MA1A3','MA1A4','MA1A5')
        """;

    // Active coworking subscriptions — mirrors SubscriptionService.resolveSubscriptionCategory:
    // coworking iff the linked product's tipo is 'mesa', else (no product tipo) a description
    // heuristic. These get a standing MA1O1 (desk + street door) grant. Virtual-office subs
    // are deliberately excluded — their access is booking-driven, not subscription-driven.
    private static final String SUBS_DESIRED_SQL = """
        SELECT s.id AS sub_id, s.contact_id
          FROM beworking.subscriptions s
          LEFT JOIN beworking.productos p ON p.id = s.producto_id
         WHERE s.active = true
           AND s.contact_id IS NOT NULL
           AND (
                 LOWER(TRIM(p.tipo)) = 'mesa'
              OR (p.tipo IS NULL AND (
                    LOWER(COALESCE(s.description,'')) LIKE '%coworking%'
                 OR LOWER(COALESCE(s.description,'')) LIKE '%mesa%'
                 OR LOWER(COALESCE(s.description,'')) LIKE '%desk%'))
               )
        """;

    private final JdbcTemplate jdbcTemplate;
    private final BeKeyAccessService beKeyAccessService;
    private final BeKeyAccessRepository accessRepository;
    private final boolean integrationEnabled;

    public BeKeyReconciliationScheduler(JdbcTemplate jdbcTemplate,
                                        BeKeyAccessService beKeyAccessService,
                                        BeKeyAccessRepository accessRepository,
                                        @Value("${akiles.integration.enabled:false}") boolean integrationEnabled) {
        this.jdbcTemplate = jdbcTemplate;
        this.beKeyAccessService = beKeyAccessService;
        this.accessRepository = accessRepository;
        this.integrationEnabled = integrationEnabled;
    }

    // Every 30 minutes (UTC). Day-pinned windows make exact timing non-critical.
    @Scheduled(cron = "0 0,30 * * * *")
    public void reconcile() {
        runOnce();
    }

    public RunResult runOnce() {
        // Master kill-switch (#244) - skip entirely (no DB query, no Akiles) when disabled.
        if (!integrationEnabled) {
            return new RunResult(0, 0);
        }

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

    // Subscription reconcile, offset 15 min from the booking reconcile. The first
    // run after go-live backfills standing MA1O1 grants for coworking subs created
    // before the integration existed; thereafter it heals drift (sub activated /
    // deactivated / re-categorised outside the create+cancel paths).
    @Scheduled(cron = "0 15,45 * * * *")
    public void reconcileSubscriptions() {
        runSubscriptionsOnce();
    }

    /**
     * Diff active coworking subscriptions against active Source.subscription grants:
     *   desired, not granted  -> grantForSubscription (coworking -> MA1O1)
     *   granted, not desired  -> revoke (sub deactivated / no longer coworking)
     * Idempotent via grant()'s (source, sourceRef) dedup. Best-effort per row.
     */
    public RunResult runSubscriptionsOnce() {
        // Master kill-switch (#244) - skip entirely when disabled.
        if (!integrationEnabled) {
            return new RunResult(0, 0);
        }

        List<Map<String, Object>> desired = jdbcTemplate.queryForList(SUBS_DESIRED_SQL);

        // Existing active subscription grants, keyed by subscription id (sourceRef).
        Map<Long, BeKeyAccess> existing = new HashMap<>();
        for (BeKeyAccess g : accessRepository.findBySourceAndRevokedAtIsNull(BeKeyAccess.Source.subscription)) {
            if (g.getSourceRef() != null) {
                existing.put(g.getSourceRef(), g);
            }
        }

        int granted = 0;
        int revoked = 0;
        Set<Long> desiredIds = new HashSet<>();

        // ADD — active coworking subs with no standing grant yet.
        for (Map<String, Object> row : desired) {
            Long subId = ((Number) row.get("sub_id")).longValue();
            desiredIds.add(subId);
            if (existing.containsKey(subId)) {
                continue;   // already granted — no Akiles call
            }
            try {
                Long contactId = ((Number) row.get("contact_id")).longValue();
                beKeyAccessService.grantForSubscription(contactId, subId, "coworking");
                granted++;
            } catch (Exception ex) {
                logger.warn("BeKey sub reconcile: grant for sub {} failed: {}", subId, ex.getMessage());
            }
        }

        // REMOVE — active grants whose sub is no longer active/coworking.
        for (Map.Entry<Long, BeKeyAccess> e : existing.entrySet()) {
            if (desiredIds.contains(e.getKey())) {
                continue;
            }
            try {
                beKeyAccessService.revoke(e.getValue().getId(), "reconcile: subscription no longer active/coworking");
                revoked++;
            } catch (Exception ex) {
                logger.warn("BeKey sub reconcile: revoke of access {} (sub {}) failed: {}",
                        e.getValue().getId(), e.getKey(), ex.getMessage());
            }
        }

        // ── Standing desk access (membership): every Usuario Mesa, free desk
        // included, always holds MA1O1. Skip those already covered by a desk
        // sub grant; revoke membership when a contact stops being Usuario Mesa.
        List<Long> deskContacts = jdbcTemplate.queryForList(
                "SELECT id FROM beworking.contact_profiles WHERE LOWER(COALESCE(tenant_type, '')) = 'usuario mesa'",
                Long.class);
        Set<Long> deskSet = new HashSet<>(deskContacts);
        Map<Long, BeKeyAccess> existingMembership = new HashMap<>();
        for (BeKeyAccess g : accessRepository.findBySourceAndRevokedAtIsNull(BeKeyAccess.Source.membership)) {
            if (g.getSourceRef() != null) {
                existingMembership.put(g.getSourceRef(), g);
            }
        }
        for (Long cid : deskContacts) {
            if (existingMembership.containsKey(cid)) {
                continue;   // already has standing membership access
            }
            boolean coveredBySub = accessRepository.findByContactIdAndRevokedAtIsNull(cid).stream()
                    .anyMatch(g -> g.getSource() == BeKeyAccess.Source.subscription);
            if (coveredBySub) {
                continue;   // their desk sub already grants MA1O1
            }
            try {
                beKeyAccessService.grantForMembership(cid);
                granted++;
            } catch (Exception ex) {
                logger.warn("BeKey membership grant for contact {} failed: {}", cid, ex.getMessage());
            }
        }
        for (Map.Entry<Long, BeKeyAccess> e : existingMembership.entrySet()) {
            if (deskSet.contains(e.getKey())) {
                continue;
            }
            try {
                beKeyAccessService.revoke(e.getValue().getId(), "reconcile: no longer Usuario Mesa");
                revoked++;
            } catch (Exception ex) {
                logger.warn("BeKey membership revoke of access {} (contact {}) failed: {}",
                        e.getValue().getId(), e.getKey(), ex.getMessage());
            }
        }

        if (granted > 0 || revoked > 0) {
            logger.info("BeKey subscription/desk reconcile: granted={} revoked={} (subs={} deskContacts={})",
                    granted, revoked, desiredIds.size(), deskSet.size());
        }
        return new RunResult(granted, revoked);
    }

    public record RunResult(int granted, int revoked) {}
}
