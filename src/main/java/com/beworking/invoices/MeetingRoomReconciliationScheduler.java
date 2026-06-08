package com.beworking.invoices;

import com.beworking.auth.EmailService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Daily report listing past-due meeting-room invoices (PT, Usuario Aulas).
 * Emails info@be-working.com with a WhatsApp button per customer so the team
 * can drive manual outreach. Empty list → no email.
 */
@Component
public class MeetingRoomReconciliationScheduler {

    private static final Logger logger = LoggerFactory.getLogger(MeetingRoomReconciliationScheduler.class);
    private static final String ADMIN_EMAIL = "info@be-working.com";

    private final MeetingRoomReconciliationService service;
    private final EmailService emailService;

    public MeetingRoomReconciliationScheduler(MeetingRoomReconciliationService service,
                                              EmailService emailService) {
        this.service = service;
        this.emailService = emailService;
    }

    @Scheduled(cron = "0 30 5 * * *")
    public void runScheduled() {
        runOnce();
    }

    public RunResult runOnce() {
        List<MeetingRoomReconciliationService.PastDueRoomInvoice> rows = service.findPastDue();
        if (rows.isEmpty()) {
            logger.info("Meeting-room reconciliation: nothing past-due today");
            return new RunResult(0, BigDecimal.ZERO, false);
        }

        BigDecimal total = rows.stream()
            .map(r -> r.total() != null ? r.total() : BigDecimal.ZERO)
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        sendReport(rows, total);
        return new RunResult(rows.size(), total, true);
    }

    private void sendReport(List<MeetingRoomReconciliationService.PastDueRoomInvoice> rows, BigDecimal totalAmount) {
        String fontStack = "-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Helvetica,Arial,sans-serif";
        String subject = "BeWorking — Meeting-room past-due " + LocalDate.now();

        StringBuilder html = new StringBuilder();
        html.append("<div style=\"font-family:").append(fontStack)
            .append(";max-width:760px;margin:24px auto;color:#1a1a1a;background:#fafafa;padding:24px\">");
        html.append("<div style='background:#fff;border:1px solid #e5e7eb;border-radius:12px;padding:28px 32px'>");

        html.append("<div style='display:flex;justify-content:space-between;align-items:baseline;border-bottom:1px solid #f0f0f0;padding-bottom:16px;margin-bottom:24px'>");
        html.append("<h1 style='margin:0;font-size:18px;font-weight:600;letter-spacing:-0.015em;color:#111'>Meeting-room past-due</h1>");
        html.append("<span style='font-size:12px;color:#6b7280'>").append(LocalDate.now()).append(" · ")
            .append(rows.size()).append(" facturas · ").append(formatEur(totalAmount)).append("</span>");
        html.append("</div>");

        html.append("<table style='border-collapse:collapse;width:100%;font-size:13px;border:1px solid #f0f0f0;border-radius:6px'>");
        html.append("<tr style='background:#fafafa;border-bottom:1px solid #f0f0f0'>")
            .append("<th style='text-align:left;padding:8px 12px;font-size:11px;font-weight:600;color:#6b7280;text-transform:uppercase'>Cliente</th>")
            .append("<th style='text-align:left;padding:8px 12px;font-size:11px;font-weight:600;color:#6b7280;text-transform:uppercase'>WhatsApp</th>")
            .append("<th style='text-align:left;padding:8px 12px;font-size:11px;font-weight:600;color:#6b7280;text-transform:uppercase'>Factura</th>")
            .append("<th style='text-align:right;padding:8px 12px;font-size:11px;font-weight:600;color:#6b7280;text-transform:uppercase'>Importe</th>")
            .append("<th style='text-align:right;padding:8px 12px;font-size:11px;font-weight:600;color:#6b7280;text-transform:uppercase'>Días</th></tr>");
        for (MeetingRoomReconciliationService.PastDueRoomInvoice r : rows) {
            String customer = (r.customerName() != null ? r.customerName() : "—")
                + (r.customerEmail() != null
                    ? "<br><span style='color:#6b7280;font-size:12px'>" + r.customerEmail() + "</span>"
                    : "");
            String waCell;
            if (r.customerPhone() != null && !r.customerPhone().isBlank()) {
                String waNumber = r.customerPhone().replaceAll("[^0-9]", "");
                // Prefill the message with greeting + amount + the customer-facing
                // Stripe payment link so the team can chase payment in one tap.
                // Falls back to a link-less message when the hosted URL is missing.
                String greeting = r.customerName() != null && !r.customerName().isBlank()
                    ? "Hola " + r.customerName() + ", " : "Hola, ";
                String payUrl = r.hostedInvoiceUrl();
                String msg = greeting + "tu recibo de BeWorking por " + formatEur(r.total())
                    + " está pendiente de pago."
                    + (payUrl != null && !payUrl.isBlank() ? " Puedes regularizarlo aquí: " + payUrl : "");
                String encoded = java.net.URLEncoder.encode(msg, java.nio.charset.StandardCharsets.UTF_8)
                    .replace("+", "%20");
                waCell = "<a href='https://wa.me/" + waNumber + "?text=" + encoded + "' "
                    + "style='display:inline-block;background:#25D366;color:#fff;text-decoration:none;"
                    + "padding:6px 12px;border-radius:14px;font-size:12px;font-weight:600;white-space:nowrap'>WhatsApp</a>";
            } else {
                waCell = "<span style='color:#9ca3af;font-size:12px'>—</span>";
            }
            String facturaRef = r.idfactura() != null ? "PT" + r.idfactura() : "—";
            html.append("<tr style='border-bottom:1px solid #f5f5f5'>")
                .append("<td style='padding:10px 12px;vertical-align:top'>").append(customer).append("</td>")
                .append("<td style='padding:10px 12px;vertical-align:top'>").append(waCell).append("</td>")
                .append("<td style='padding:10px 12px;vertical-align:top;font-family:ui-monospace,SFMono-Regular,Menlo,monospace;font-size:11px;color:#6b7280'>")
                .append(facturaRef).append("</td>")
                .append("<td style='padding:10px 12px;vertical-align:top;text-align:right;white-space:nowrap;font-variant-numeric:tabular-nums;font-weight:600;color:#111'>")
                .append(formatEur(r.total())).append("</td>")
                .append("<td style='padding:10px 12px;vertical-align:top;text-align:right;color:#dc2626;font-weight:600'>")
                .append(r.daysPastDue()).append("</td></tr>");
        }
        html.append("</table>");

        html.append("</div>");
        html.append("<p style='margin:12px 0 0;font-size:11px;color:#9ca3af;text-align:center'>BeWorking · Meeting-room reconciliation</p>");
        html.append("</div>");

        try {
            emailService.sendHtml(ADMIN_EMAIL, subject, html.toString());
        } catch (Exception e) {
            logger.error("Failed to send meeting-room past-due report: {}", e.getMessage(), e);
        }
    }

    private static String formatEur(BigDecimal n) {
        if (n == null) return "€0.00";
        return String.format(Locale.ROOT, "€%,.2f", n);
    }

    public record RunResult(int facturasPastDue, BigDecimal totalAmount, boolean emailSent) {}
}
