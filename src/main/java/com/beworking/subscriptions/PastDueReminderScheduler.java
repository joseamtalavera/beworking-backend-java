package com.beworking.subscriptions;

import com.beworking.auth.EmailService;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Daily past-due reminder cron.
 *
 * Sends up to 3 dunning emails to customers (day 1, day 3, day 7) for:
 *   - Stripe subscriptions in past_due status
 *   - One-off invoices (meeting rooms / desks) that are 24h+ Pendiente
 *
 * Each customer email includes a "Paga aquí" CTA pointing at the Stripe
 * hosted invoice URL (handles card and SEPA — Stripe's page covers both).
 *
 * After per-customer mail, a single internal digest is emailed to info@
 * with every current past-due record + a WhatsApp button per row, so the
 * team can drive manual follow-up.
 */
@Component
public class PastDueReminderScheduler {

    private static final Logger logger = LoggerFactory.getLogger(PastDueReminderScheduler.class);
    private static final String ADMIN_EMAIL = "info@be-working.com";
    private static final String[] ACCOUNTS = {"GT", "PT"};

    // Cadence thresholds: emailCount → minimum days since reference timestamp.
    // emailCount==0: 1 day since past-due started (first nudge)
    // emailCount==1: 2 days since last email (≈ day 3 of past-due)
    // emailCount==2: 4 days since last email (≈ day 7 of past-due)
    private static final int[] DAYS_SINCE_REFERENCE = {1, 2, 4};

    private final JdbcTemplate jdbcTemplate;
    private final EmailService emailService;
    private final RestClient http;
    private final String paymentsBaseUrl;

    public PastDueReminderScheduler(JdbcTemplate jdbcTemplate,
                                    EmailService emailService,
                                    @Value("${app.payments.base-url:}") String paymentsBaseUrl) {
        this.jdbcTemplate = jdbcTemplate;
        this.emailService = emailService;
        this.paymentsBaseUrl = paymentsBaseUrl;
        this.http = RestClient.create();
    }

    @Scheduled(cron = "0 0 7 * * *")
    public void runScheduled() {
        runOnce();
    }

    public RunResult runOnce() {
        logger.info("Past-due reminder cron started");

        List<PastDueRecord> subs = collectPastDueSubs();
        List<PastDueRecord> invoices = collectPastDueInvoices();

        int customerEmailsSent = 0;
        for (PastDueRecord r : subs) {
            if (shouldSendNow(r) && sendDunningEmail(r)) {
                bumpSubCounter(r.recordId);
                customerEmailsSent++;
            }
        }
        for (PastDueRecord r : invoices) {
            if (shouldSendNow(r) && sendDunningEmail(r)) {
                bumpInvoiceCounter(r.recordId);
                customerEmailsSent++;
            }
        }

        boolean digestSent = false;
        if (!subs.isEmpty() || !invoices.isEmpty()) {
            sendInternalDigest(subs, invoices);
            digestSent = true;
        }

        logger.info("Past-due reminder done: subs={} invoices={} customerEmails={} digest={}",
            subs.size(), invoices.size(), customerEmailsSent, digestSent);
        return new RunResult(customerEmailsSent, subs.size(), invoices.size(), digestSent);
    }

    // ─── data collection ────────────────────────────────────────────────

    private List<PastDueRecord> collectPastDueSubs() {
        List<PastDueRecord> out = new ArrayList<>();
        if (paymentsBaseUrl == null || paymentsBaseUrl.isBlank()) {
            logger.warn("paymentsBaseUrl not configured — skipping past-due sub fetch");
            return out;
        }

        for (String account : ACCOUNTS) {
            Map<String, Object> stripeData;
            try {
                stripeData = http.get()
                    .uri(paymentsBaseUrl + "/api/reconciliation/" + account)
                    .retrieve()
                    .body(new ParameterizedTypeReference<Map<String, Object>>() {});
            } catch (Exception e) {
                logger.error("Failed to fetch past-due subs from stripe-service for {}: {}", account, e.getMessage(), e);
                continue;
            }
            if (stripeData == null) continue;

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> pastDue = (List<Map<String, Object>>) stripeData.getOrDefault("pastDueSubs", List.of());

            for (Map<String, Object> raw : pastDue) {
                String stripeSubId = (String) raw.get("subscriptionId");
                if (stripeSubId == null) continue;

                Map<String, Object> dbRow;
                try {
                    dbRow = jdbcTemplate.queryForMap("""
                        SELECT s.id, s.contact_id, cp.name, cp.email_primary, cp.phone_primary,
                               s.past_due_email_count, s.last_past_due_email_at, s.past_due_period_start
                          FROM beworking.subscriptions s
                          JOIN beworking.contact_profiles cp ON cp.id = s.contact_id
                         WHERE s.stripe_subscription_id = ?
                         LIMIT 1
                        """, stripeSubId);
                } catch (EmptyResultDataAccessException ignored) {
                    continue;
                }

                Long subId = ((Number) dbRow.get("id")).longValue();
                LocalDateTime pdStart = toLocal(dbRow.get("past_due_period_start"));
                // First time we see this sub past-due → stamp the period start now.
                if (pdStart == null) {
                    LocalDateTime now = LocalDateTime.now();
                    jdbcTemplate.update(
                        "UPDATE beworking.subscriptions SET past_due_period_start = ? WHERE id = ?",
                        now, subId);
                    pdStart = now;
                }

                PastDueRecord rec = new PastDueRecord();
                rec.kind = "sub";
                rec.recordId = subId;
                rec.contactId = ((Number) dbRow.get("contact_id")).longValue();
                rec.customerName = (String) dbRow.get("name");
                rec.customerEmail = (String) dbRow.get("email_primary");
                rec.customerPhone = (String) dbRow.get("phone_primary");
                rec.amount = toBigDecimal(raw.get("amountDue"));
                rec.hostedInvoiceUrl = (String) raw.get("hostedInvoiceUrl");
                rec.stripeRef = stripeSubId;
                rec.pastDueSince = pdStart;
                rec.emailCount = ((Number) dbRow.getOrDefault("past_due_email_count", 0)).intValue();
                rec.lastEmailAt = toLocal(dbRow.get("last_past_due_email_at"));
                out.add(rec);
            }
        }
        return out;
    }

    private List<PastDueRecord> collectPastDueInvoices() {
        // 24h+ Pendiente facturas, keyword-matched on estado to mirror existing
        // pendingByAccount logic. idfactura<100000 filter avoids the historical
        // outliers (see CLAUDE.md note).
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
            SELECT f.id, f.idfactura, f.total, f.creacionfecha, f.stripeinvoiceid,
                   f.dunning_email_count, f.last_dunning_email_at,
                   f.idcliente AS contact_id, cp.name, cp.email_primary, cp.phone_primary,
                   UPPER(COALESCE(NULLIF(f.holdedcuenta, ''), 'PT')) AS cuenta
              FROM beworking.facturas f
              JOIN beworking.contact_profiles cp ON cp.id = f.idcliente
             WHERE f.creacionfecha < NOW() - INTERVAL '1 day'
               AND f.idfactura < 100000
               AND (LOWER(COALESCE(f.estado, '')) LIKE '%pend%'
                 OR LOWER(COALESCE(f.estado, '')) LIKE '%confir%'
                 OR LOWER(COALESCE(f.estado, '')) LIKE '%fact%'
                 OR LOWER(COALESCE(f.estado, '')) LIKE '%invoice%'
                 OR LOWER(COALESCE(f.estado, '')) LIKE '%created%')
            """);

        List<PastDueRecord> out = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            PastDueRecord rec = new PastDueRecord();
            rec.kind = "invoice";
            rec.recordId = ((Number) row.get("id")).longValue();
            rec.contactId = row.get("contact_id") != null ? ((Number) row.get("contact_id")).longValue() : null;
            rec.customerName = (String) row.get("name");
            rec.customerEmail = (String) row.get("email_primary");
            rec.customerPhone = (String) row.get("phone_primary");
            rec.amount = toBigDecimal(row.get("total"));
            rec.pastDueSince = toLocal(row.get("creacionfecha"));
            rec.emailCount = ((Number) row.getOrDefault("dunning_email_count", 0)).intValue();
            rec.lastEmailAt = toLocal(row.get("last_dunning_email_at"));
            String cuenta = (String) row.get("cuenta");
            String stripeInvoiceId = (String) row.get("stripeinvoiceid");
            rec.stripeRef = stripeInvoiceId != null ? stripeInvoiceId
                : String.format("%s%s", cuenta, row.get("idfactura"));
            rec.hostedInvoiceUrl = stripeInvoiceId != null ? fetchHostedUrl(stripeInvoiceId, cuenta) : null;
            out.add(rec);
        }
        return out;
    }

    private String fetchHostedUrl(String stripeInvoiceId, String account) {
        if (paymentsBaseUrl == null || paymentsBaseUrl.isBlank()) return null;
        try {
            Map<String, Object> resp = http.get()
                .uri(paymentsBaseUrl + "/api/invoices/" + stripeInvoiceId + "/hosted-url?account=" + account)
                .retrieve()
                .body(new ParameterizedTypeReference<Map<String, Object>>() {});
            return resp != null ? (String) resp.get("hostedInvoiceUrl") : null;
        } catch (Exception e) {
            logger.warn("Failed to fetch hosted URL for invoice {}: {}", stripeInvoiceId, e.getMessage());
            return null;
        }
    }

    // ─── cadence decision ───────────────────────────────────────────────

    private boolean shouldSendNow(PastDueRecord r) {
        if (r.emailCount >= 3) return false;
        if (r.customerEmail == null || r.customerEmail.isBlank()) return false;

        LocalDateTime reference = r.emailCount == 0 ? r.pastDueSince : r.lastEmailAt;
        if (reference == null) return false;

        long days = ChronoUnit.DAYS.between(reference, LocalDateTime.now());
        return days >= DAYS_SINCE_REFERENCE[r.emailCount];
    }

    // ─── customer email ────────────────────────────────────────────────

    private boolean sendDunningEmail(PastDueRecord r) {
        int step = Math.min(r.emailCount, 2); // 0,1,2 → templates 1/2/3
        String subject = subjectFor(step);
        String html = dunningHtml(r, step);
        try {
            emailService.sendBccInfo(r.customerEmail, subject, html);
            return true;
        } catch (Exception e) {
            logger.error("Failed to send dunning email to {} for {}/{}: {}",
                r.customerEmail, r.kind, r.recordId, e.getMessage(), e);
            return false;
        }
    }

    private String subjectFor(int step) {
        return switch (step) {
            case 0 -> "Pago pendiente — BeWorking";
            case 1 -> "Recordatorio: pago pendiente — BeWorking";
            default -> "Aviso final: pago pendiente — BeWorking";
        };
    }

    private String dunningHtml(PastDueRecord r, int step) {
        String fontStack = "-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Helvetica,Arial,sans-serif";
        String name = r.customerName != null && !r.customerName.isBlank() ? r.customerName : "Hola";
        String greeting = "Hola " + name + ",";
        String bodyText = switch (step) {
            case 0 -> "Hemos detectado un pago pendiente en tu cuenta de BeWorking. "
                + "Puedes liquidarlo en un clic desde el siguiente enlace.";
            case 1 -> "Te recordamos que sigues con un pago pendiente. "
                + "Si ya lo has realizado, ignora este mensaje. Si no, puedes hacerlo desde el enlace de abajo.";
            default -> "Este es el aviso final sobre tu pago pendiente. "
                + "Si no recibimos el pago, tu suscripción podría ser suspendida. "
                + "Por favor regulariza tu situación cuanto antes.";
        };

        StringBuilder html = new StringBuilder();
        html.append("<div style=\"font-family:").append(fontStack)
            .append(";max-width:560px;margin:24px auto;color:#1a1a1a;background:#fafafa;padding:24px\">");
        html.append("<div style='background:#fff;border:1px solid #e5e7eb;border-radius:12px;padding:32px'>");
        html.append("<p style='margin:0 0 4px;font-size:11px;letter-spacing:0.18em;text-transform:uppercase;color:#6b7280;font-weight:600'>BeWorking</p>");
        html.append("<h1 style='margin:0 0 24px;font-size:20px;font-weight:600;color:#111'>")
            .append(step == 2 ? "Aviso final" : "Pago pendiente").append("</h1>");
        html.append("<p style='margin:0 0 12px;font-size:14px;line-height:1.55;color:#374151'>").append(greeting).append("</p>");
        html.append("<p style='margin:0 0 20px;font-size:14px;line-height:1.55;color:#374151'>").append(bodyText).append("</p>");

        html.append("<div style='background:#f9fafb;border:1px solid #e5e7eb;border-radius:8px;padding:16px 20px;margin:20px 0'>");
        html.append("<div style='font-size:11px;text-transform:uppercase;letter-spacing:0.05em;color:#6b7280;font-weight:600;margin-bottom:4px'>Importe pendiente</div>");
        html.append("<div style='font-size:24px;font-weight:700;color:#111;font-variant-numeric:tabular-nums'>").append(formatEur(r.amount)).append("</div>");
        html.append("<div style='font-size:12px;color:#6b7280;margin-top:6px;font-family:ui-monospace,SFMono-Regular,Menlo,monospace'>Ref: ").append(r.stripeRef).append("</div>");
        html.append("</div>");

        if (r.hostedInvoiceUrl != null && !r.hostedInvoiceUrl.isBlank()) {
            html.append("<div style='margin:24px 0;text-align:center'>");
            html.append("<a href='").append(r.hostedInvoiceUrl)
                .append("' style='display:inline-block;background:#111;color:#fff;text-decoration:none;padding:12px 24px;border-radius:8px;font-size:14px;font-weight:600'>Paga aquí</a>");
            html.append("</div>");
        } else {
            html.append("<p style='margin:0 0 16px;font-size:13px;color:#6b7280'>Para regularizar el pago contacta con nosotros en info@be-working.com.</p>");
        }

        html.append("<p style='margin:24px 0 0;font-size:12px;color:#9ca3af'>Si ya has realizado el pago en las últimas horas, este mensaje se cruzó con el procesamiento. Disculpa las molestias.</p>");
        html.append("</div>");
        html.append("<p style='margin:12px 0 0;font-size:11px;color:#9ca3af;text-align:center'>BeWorking · info@be-working.com</p>");
        html.append("</div>");
        return html.toString();
    }

    // ─── internal digest ────────────────────────────────────────────────

    private void sendInternalDigest(List<PastDueRecord> subs, List<PastDueRecord> invoices) {
        String fontStack = "-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Helvetica,Arial,sans-serif";
        String subject = "BeWorking — Past-due " + java.time.LocalDate.now();

        StringBuilder html = new StringBuilder();
        html.append("<div style=\"font-family:").append(fontStack)
            .append(";max-width:760px;margin:24px auto;color:#1a1a1a;background:#fafafa;padding:24px\">");
        html.append("<div style='background:#fff;border:1px solid #e5e7eb;border-radius:12px;padding:28px 32px'>");
        html.append("<h1 style='margin:0 0 4px;font-size:18px;font-weight:600;color:#111'>Past-due — resumen diario</h1>");
        html.append("<p style='margin:0 0 20px;font-size:12px;color:#6b7280'>").append(java.time.LocalDate.now())
            .append(" · ").append(subs.size()).append(" suscripciones · ").append(invoices.size()).append(" facturas</p>");

        appendDigestTable(html, "Suscripciones past-due", subs);
        appendDigestTable(html, "Facturas pendientes (24h+)", invoices);

        html.append("</div>");
        html.append("<p style='margin:12px 0 0;font-size:11px;color:#9ca3af;text-align:center'>BeWorking · Past-due reminder cron</p>");
        html.append("</div>");

        try {
            emailService.sendHtml(ADMIN_EMAIL, subject, html.toString());
        } catch (Exception e) {
            logger.error("Failed to send past-due digest: {}", e.getMessage(), e);
        }
    }

    private void appendDigestTable(StringBuilder html, String title, List<PastDueRecord> records) {
        if (records.isEmpty()) return;
        html.append("<h2 style='margin:24px 0 12px;font-size:14px;font-weight:600;color:#374151'>").append(title).append("</h2>");
        html.append("<table style='border-collapse:collapse;width:100%;font-size:13px;border:1px solid #f0f0f0;border-radius:6px'>");
        html.append("<tr style='background:#fafafa;border-bottom:1px solid #f0f0f0'>")
            .append("<th style='text-align:left;padding:8px 12px;font-size:11px;font-weight:600;color:#6b7280;text-transform:uppercase'>Cliente</th>")
            .append("<th style='text-align:left;padding:8px 12px;font-size:11px;font-weight:600;color:#6b7280;text-transform:uppercase'>WhatsApp</th>")
            .append("<th style='text-align:left;padding:8px 12px;font-size:11px;font-weight:600;color:#6b7280;text-transform:uppercase'>Ref</th>")
            .append("<th style='text-align:right;padding:8px 12px;font-size:11px;font-weight:600;color:#6b7280;text-transform:uppercase'>Importe</th>")
            .append("<th style='text-align:right;padding:8px 12px;font-size:11px;font-weight:600;color:#6b7280;text-transform:uppercase'>Emails</th></tr>");
        for (PastDueRecord r : records) {
            String customer = (r.customerName != null ? r.customerName : "—")
                + (r.customerEmail != null ? "<br><span style='color:#6b7280;font-size:12px'>" + r.customerEmail + "</span>" : "");
            String waCell;
            if (r.customerPhone != null && !r.customerPhone.isBlank()) {
                String waNumber = r.customerPhone.replaceAll("[^0-9]", "");
                waCell = "<a href='https://wa.me/" + waNumber + "' "
                    + "style='display:inline-block;background:#25D366;color:#fff;text-decoration:none;"
                    + "padding:6px 12px;border-radius:14px;font-size:12px;font-weight:600;white-space:nowrap'>WhatsApp</a>";
            } else {
                waCell = "<span style='color:#9ca3af;font-size:12px'>—</span>";
            }
            html.append("<tr style='border-bottom:1px solid #f5f5f5'>")
                .append("<td style='padding:10px 12px;vertical-align:top'>").append(customer).append("</td>")
                .append("<td style='padding:10px 12px;vertical-align:top'>").append(waCell).append("</td>")
                .append("<td style='padding:10px 12px;vertical-align:top;font-family:ui-monospace,SFMono-Regular,Menlo,monospace;font-size:11px;color:#6b7280'>").append(r.stripeRef != null ? r.stripeRef : "—").append("</td>")
                .append("<td style='padding:10px 12px;vertical-align:top;text-align:right;white-space:nowrap;font-variant-numeric:tabular-nums;font-weight:600;color:#111'>").append(formatEur(r.amount)).append("</td>")
                .append("<td style='padding:10px 12px;vertical-align:top;text-align:right'>").append(r.emailCount).append(" / 3</td></tr>");
        }
        html.append("</table>");
    }

    // ─── counter persistence ────────────────────────────────────────────

    private void bumpSubCounter(Long subId) {
        jdbcTemplate.update("""
            UPDATE beworking.subscriptions
               SET past_due_email_count = past_due_email_count + 1,
                   last_past_due_email_at = NOW()
             WHERE id = ?
            """, subId);
    }

    private void bumpInvoiceCounter(Long facturaId) {
        jdbcTemplate.update("""
            UPDATE beworking.facturas
               SET dunning_email_count = dunning_email_count + 1,
                   last_dunning_email_at = NOW()
             WHERE id = ?
            """, facturaId);
    }

    // ─── helpers ────────────────────────────────────────────────────────

    private static LocalDateTime toLocal(Object v) {
        if (v == null) return null;
        if (v instanceof LocalDateTime ldt) return ldt;
        if (v instanceof java.sql.Timestamp ts) return ts.toLocalDateTime();
        return null;
    }

    private static BigDecimal toBigDecimal(Object v) {
        if (v == null) return BigDecimal.ZERO;
        if (v instanceof BigDecimal bd) return bd;
        return new BigDecimal(v.toString());
    }

    private static String formatEur(BigDecimal n) {
        if (n == null) return "€0.00";
        return String.format(Locale.ROOT, "€%,.2f", n);
    }

    // ─── record + result ────────────────────────────────────────────────

    private static class PastDueRecord {
        String kind;            // "sub" or "invoice"
        Long recordId;          // subscriptions.id or facturas.id
        Long contactId;
        String customerName;
        String customerEmail;
        String customerPhone;
        String stripeRef;       // sub_xxx, in_xxx, or PT00123 fallback
        String hostedInvoiceUrl;
        BigDecimal amount;
        LocalDateTime pastDueSince;
        int emailCount;
        LocalDateTime lastEmailAt;
    }

    public record RunResult(int customerEmailsSent, int subsPastDue, int invoicesPastDue, boolean digestSent) {}
}
