package com.beworking.bekey;

import com.beworking.auth.EmailService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * One-shot BeKey launch announcement to current access holders (#255).
 * Audience = every contact with an active (non-revoked, in-window) bekey_access
 * grant — i.e. anyone who can open a door today. Idempotent via
 * bekey_announcement_log: re-running only sends to those not yet emailed, so a
 * mid-send hiccup is safe to resume. A single copy also goes to info@.
 */
@Service
public class BeKeyAnnouncementService {

    private static final Logger LOGGER = LoggerFactory.getLogger(BeKeyAnnouncementService.class);
    private static final String INFO_EMAIL = "info@be-working.com";

    private static final String RECIPIENTS_SQL = """
        SELECT cp.id,
               cp.name,
               LOWER(COALESCE(NULLIF(cp.email_primary, ''), NULLIF(cp.email_secondary, ''))) AS email,
               COALESCE(cp.tenant_type, '—') AS tenant_type
          FROM beworking.contact_profiles cp
         WHERE COALESCE(NULLIF(cp.email_primary, ''), NULLIF(cp.email_secondary, '')) IS NOT NULL
           AND (
                 -- a) holds a standing/active grant right now: desk SUBS + live
                 --    booking grants (membership/tag-based grants are retired).
                 --    Excludes transient share guests.
                 EXISTS (
                   SELECT 1 FROM beworking.bekey_access a
                    WHERE a.contact_id = cp.id
                      AND a.revoked_at IS NULL
                      AND a.starts_at <= NOW()
                      AND (a.ends_at IS NULL OR a.ends_at > NOW())
                      AND a.source <> 'shared'
                 )
                 -- b) anyone with a current/FUTURE room booking from today onward
              OR EXISTS (
                   SELECT 1 FROM beworking.bloqueos b
                    WHERE b.id_cliente = cp.id
                      AND (b.fin_indefinido = 1 OR b.fecha_fin >= CURRENT_DATE)
                 )
               )
        """;

    private final JdbcTemplate jdbc;
    private final EmailService emailService;

    public BeKeyAnnouncementService(JdbcTemplate jdbc, EmailService emailService) {
        this.jdbc = jdbc;
        this.emailService = emailService;
    }

    /** Read-only: who'd be emailed now (no send). Used by dryRun. */
    public Result preview() {
        List<Map<String, Object>> rows = jdbc.queryForList(RECIPIENTS_SQL);

        Map<String, Map<String, Object>> byEmail = new LinkedHashMap<>();
        for (Map<String, Object> r : rows) {
            String email = (String) r.get("email");
            if (email != null && !email.isBlank()) {
                byEmail.putIfAbsent(email, r);
            }
        }
        Set<String> alreadySent = new HashSet<>(
                jdbc.queryForList("SELECT email FROM beworking.bekey_announcement_log", String.class));
        long pending = byEmail.values().stream()
                .filter(r -> !alreadySent.contains((String) r.get("email")))
                .count();
        Map<String, Integer> breakdown = new TreeMap<>();
        for (Map<String, Object> r : byEmail.values()) {
            breakdown.merge(String.valueOf(r.getOrDefault("tenant_type", "—")), 1, Integer::sum);
        }
        LOGGER.info("BeKey announcement preview: holders={} pending={} alreadySent={}",
                byEmail.size(), pending, alreadySent.size());
        return new Result(true, byEmail.size(), (int) pending, alreadySent.size(), 0, 0, breakdown);
    }

    /**
     * Sends to the not-yet-emailed audience, async so a bulk run never trips the
     * ALB gateway timeout. Idempotent via the log; safe to call again.
     */
    @org.springframework.scheduling.annotation.Async
    public void sendAsync() {
        List<Map<String, Object>> rows = jdbc.queryForList(RECIPIENTS_SQL);
        Map<String, Map<String, Object>> byEmail = new LinkedHashMap<>();
        for (Map<String, Object> r : rows) {
            String email = (String) r.get("email");
            if (email != null && !email.isBlank()) byEmail.putIfAbsent(email, r);
        }
        Set<String> alreadySent = new HashSet<>(
                jdbc.queryForList("SELECT email FROM beworking.bekey_announcement_log", String.class));

        int sent = 0, failed = 0;
        for (Map<String, Object> r : byEmail.values()) {
            String email = (String) r.get("email");
            if (alreadySent.contains(email)) continue;
            try {
                emailService.sendBeKeyAnnouncement(email, (String) r.get("name"));
                jdbc.update("INSERT INTO beworking.bekey_announcement_log(email) VALUES (?) ON CONFLICT (email) DO NOTHING", email);
                sent++;
            } catch (Exception e) {
                failed++;
                LOGGER.warn("BeKey announcement to {} failed: {}", email, e.getMessage());
            }
        }
        if (sent > 0) {
            try { emailService.sendBeKeyAnnouncement(INFO_EMAIL, "Equipo BeWorking"); }
            catch (Exception e) { LOGGER.warn("BeKey announcement info@ copy failed: {}", e.getMessage()); }
        }
        LOGGER.info("BeKey announcement SENT (async): sent={} failed={}", sent, failed);
    }

    public record Result(boolean dryRun, int holders, int pending, int alreadySent,
                         int sent, int failed, Map<String, Integer> breakdown) {}
}
