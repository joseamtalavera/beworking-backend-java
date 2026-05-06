package com.beworking.contacts;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public endpoint that returns a 1×1 transparent GIF and logs an open event.
 *
 *   GET /api/track/open?c={contactId}&t={templateNumber}&type={recovery|reengagement}
 *
 * Mounted under /api/track so it inherits the /api/* nginx pass-through but
 * remains unauthenticated (recipients can't be expected to log in to load
 * an inline image). All four params are best-effort — bad input is logged
 * but always returns the GIF to avoid broken images in the email.
 */
@RestController
@RequestMapping("/api/track")
public class EmailTrackingController {

    private static final Logger logger = LoggerFactory.getLogger(EmailTrackingController.class);

    // 43-byte minimum 1x1 transparent GIF. Smallest possible image payload.
    private static final byte[] PIXEL = new byte[] {
        0x47, 0x49, 0x46, 0x38, 0x39, 0x61, 0x01, 0x00, 0x01, 0x00,
        (byte) 0x80, 0x00, 0x00, 0x00, 0x00, 0x00, (byte) 0xff, (byte) 0xff, (byte) 0xff,
        0x21, (byte) 0xf9, 0x04, 0x01, 0x00, 0x00, 0x00, 0x00, 0x2c,
        0x00, 0x00, 0x00, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00, 0x02,
        0x02, 0x44, 0x01, 0x00, 0x3b
    };

    private final JdbcTemplate jdbcTemplate;

    public EmailTrackingController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @GetMapping(value = "/open", produces = MediaType.IMAGE_GIF_VALUE)
    public ResponseEntity<byte[]> trackOpen(
        @RequestParam(value = "c", required = false) Long contactId,
        @RequestParam(value = "t", required = false, defaultValue = "0") Integer templateNumber,
        @RequestParam(value = "type", required = false, defaultValue = "recovery") String templateType,
        HttpServletRequest request
    ) {
        try {
            if (contactId != null && templateNumber != null) {
                String userAgent = request.getHeader("User-Agent");
                String ip = firstNonBlank(
                    request.getHeader("X-Forwarded-For"),
                    request.getRemoteAddr()
                );
                jdbcTemplate.update("""
                    INSERT INTO beworking.email_opens
                        (contact_id, template_type, template_number, opened_at, user_agent, ip)
                    VALUES (?, ?, ?, NOW(), ?, ?)
                    """,
                    contactId,
                    sanitizeType(templateType),
                    templateNumber,
                    truncate(userAgent, 4000),
                    truncate(ip, 64));
            }
        } catch (Exception e) {
            // Tracking is best-effort — never let a failure surface to the recipient.
            logger.warn("Failed to log email open: contact={} template={} type={} err={}",
                contactId, templateNumber, templateType, e.getMessage());
        }
        return ResponseEntity.ok()
            .header("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0")
            .header("Pragma", "no-cache")
            .body(PIXEL);
    }

    private static String sanitizeType(String type) {
        if (type == null) return "recovery";
        return switch (type.toLowerCase()) {
            case "reengagement", "reactivation" -> "reengagement";
            default -> "recovery";
        };
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }

    private static String truncate(String value, int max) {
        if (value == null) return null;
        return value.length() <= max ? value : value.substring(0, max);
    }
}
