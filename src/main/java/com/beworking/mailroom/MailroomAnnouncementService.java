package com.beworking.mailroom;

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
 * One-shot Business Address / Mailbox announcement (mail scanning + QR package
 * pickup + tracking) to ALL members with an active subscription.
 *
 * Audience = every contact with an ACTIVE subscription (subscriptions.active =
 * true) and a valid email — deliberately NOT driven by tenant_type tags (that
 * tag matches ~120 churned contacts and caused an over-broad blast on
 * 2026-06-09). We send to all active subs rather than trying to classify
 * VO/coworking, because product-tipo/description classification under-counts
 * coworking badly. The breakdown is a best-effort coworking/other label for the
 * dry-run only — it does NOT gate who receives the email. Idempotent via
 * mailroom_announcement_log, so a mid-send hiccup is safe to resume. A single
 * copy also goes to info@.
 */
@Service
public class MailroomAnnouncementService {

    private static final Logger LOGGER = LoggerFactory.getLogger(MailroomAnnouncementService.class);
    private static final String INFO_EMAIL = "info@be-working.com";

    private static final String RECIPIENTS_SQL = """
        SELECT cp.id,
               cp.name,
               LOWER(COALESCE(NULLIF(cp.email_primary, ''), NULLIF(cp.email_secondary, ''))) AS email,
               CASE
                 WHEN LOWER(TRIM(p.tipo)) = 'mesa'
                   OR LOWER(COALESCE(s.description,'')) LIKE '%coworking%'
                   OR LOWER(COALESCE(s.description,'')) LIKE '%mesa%'
                   OR LOWER(COALESCE(s.description,'')) LIKE '%desk%'        THEN 'coworking'
                 ELSE 'virtual_office_or_other'
               END AS category
          FROM beworking.contact_profiles cp
          JOIN beworking.subscriptions s ON s.contact_id = cp.id AND s.active = true
          LEFT JOIN beworking.productos p ON p.id = s.producto_id
         WHERE COALESCE(NULLIF(cp.email_primary, ''), NULLIF(cp.email_secondary, '')) IS NOT NULL
        """;

    private final JdbcTemplate jdbc;
    private final EmailService emailService;

    public MailroomAnnouncementService(JdbcTemplate jdbc, EmailService emailService) {
        this.jdbc = jdbc;
        this.emailService = emailService;
    }

    /** Read-only: who'd be emailed now (no send). Used by dryRun. */
    public Result preview() {
        Map<String, Map<String, Object>> byEmail = dedupedRecipients();
        Set<String> alreadySent = sentLog();
        long pending = byEmail.values().stream()
                .filter(r -> !alreadySent.contains((String) r.get("email")))
                .count();
        Map<String, Integer> breakdown = new TreeMap<>();
        for (Map<String, Object> r : byEmail.values()) {
            breakdown.merge(String.valueOf(r.getOrDefault("category", "—")), 1, Integer::sum);
        }
        LOGGER.info("Mailroom announcement preview: members={} pending={} alreadySent={}",
                byEmail.size(), pending, alreadySent.size());
        return new Result(true, byEmail.size(), (int) pending, alreadySent.size(), 0, 0, breakdown);
    }

    /**
     * Sends to the not-yet-emailed audience, async so a bulk run never trips the
     * ALB gateway timeout. Idempotent via the log; safe to call again.
     */
    @org.springframework.scheduling.annotation.Async
    public void sendAsync() {
        Map<String, Map<String, Object>> byEmail = dedupedRecipients();
        Set<String> alreadySent = sentLog();

        int sent = 0, failed = 0;
        for (Map<String, Object> r : byEmail.values()) {
            String email = (String) r.get("email");
            if (alreadySent.contains(email)) continue;
            try {
                emailService.sendMailroomAnnouncement(email, (String) r.get("name"));
                jdbc.update("INSERT INTO beworking.mailroom_announcement_log(email) VALUES (?) ON CONFLICT (email) DO NOTHING", email);
                sent++;
            } catch (Exception e) {
                failed++;
                LOGGER.warn("Mailroom announcement to {} failed: {}", email, e.getMessage());
            }
        }
        if (sent > 0) {
            try { emailService.sendMailroomAnnouncement(INFO_EMAIL, "Equipo BeWorking"); }
            catch (Exception e) { LOGGER.warn("Mailroom announcement info@ copy failed: {}", e.getMessage()); }
        }
        LOGGER.info("Mailroom announcement SENT (async): sent={} failed={}", sent, failed);
    }

    /** One row per distinct email (first wins). */
    private Map<String, Map<String, Object>> dedupedRecipients() {
        List<Map<String, Object>> rows = jdbc.queryForList(RECIPIENTS_SQL);
        Map<String, Map<String, Object>> byEmail = new LinkedHashMap<>();
        for (Map<String, Object> r : rows) {
            String email = (String) r.get("email");
            if (email != null && !email.isBlank()) byEmail.putIfAbsent(email, r);
        }
        return byEmail;
    }

    private Set<String> sentLog() {
        return new HashSet<>(jdbc.queryForList("SELECT email FROM beworking.mailroom_announcement_log", String.class));
    }

    public record Result(boolean dryRun, int members, int pending, int alreadySent,
                         int sent, int failed, Map<String, Integer> breakdown) {}
}
