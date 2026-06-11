package com.beworking.reports;

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
 * Daily audit: paid facturas where DB total disagrees with Stripe amount_paid
 * by more than €0.01. Stripe is authoritative. Empty list → no email.
 */
@Component
public class PriceDiscrepancyAuditScheduler {

    private static final Logger logger = LoggerFactory.getLogger(PriceDiscrepancyAuditScheduler.class);
    private static final String ADMIN_EMAIL = "info@be-working.com";

    private final PriceDiscrepancyService service;
    private final EmailService emailService;

    public PriceDiscrepancyAuditScheduler(PriceDiscrepancyService service, EmailService emailService) {
        this.service = service;
        this.emailService = emailService;
    }

    @Scheduled(cron = "0 0 6 * * *")
    public void runScheduled() {
        runOnce();
    }

    public RunResult runOnce() {
        List<PriceDiscrepancyService.Discrepancy> rows = service.findRecent();
        // Always snapshot — even an empty result, so the dashboard can show
        // "0 discrepancies, last run X" instead of going stale or re-scanning.
        service.persist(rows);
        if (rows.isEmpty()) {
            logger.info("Price discrepancy audit: nothing diverging today");
            return new RunResult(0, false);
        }
        sendReport(rows);
        return new RunResult(rows.size(), true);
    }

    private void sendReport(List<PriceDiscrepancyService.Discrepancy> rows) {
        String fontStack = "-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Helvetica,Arial,sans-serif";
        String subject = "BeWorking — Price discrepancies " + LocalDate.now();

        StringBuilder html = new StringBuilder();
        html.append("<div style=\"font-family:").append(fontStack)
            .append(";max-width:760px;margin:24px auto;color:#1a1a1a;background:#fafafa;padding:24px\">");
        html.append("<div style='background:#fff;border:1px solid #e5e7eb;border-radius:12px;padding:28px 32px'>");

        html.append("<div style='display:flex;justify-content:space-between;align-items:baseline;border-bottom:1px solid #f0f0f0;padding-bottom:16px;margin-bottom:24px'>");
        html.append("<h1 style='margin:0;font-size:18px;font-weight:600;letter-spacing:-0.015em;color:#111'>Price discrepancies</h1>");
        html.append("<span style='font-size:12px;color:#6b7280'>").append(LocalDate.now()).append(" · ")
            .append(rows.size()).append(" facturas</span>");
        html.append("</div>");

        html.append("<p style='margin:0 0 16px;font-size:13px;color:#6b7280'>Paid facturas (last 30 días) donde el total de la base de datos no coincide con <code>amount_paid</code> de Stripe. Stripe es la fuente de verdad — ajusta la BBDD o investiga el caso.</p>");

        html.append("<table style='border-collapse:collapse;width:100%;font-size:13px;border:1px solid #f0f0f0;border-radius:6px'>");
        html.append("<tr style='background:#fafafa;border-bottom:1px solid #f0f0f0'>")
            .append("<th style='text-align:left;padding:8px 12px;font-size:11px;font-weight:600;color:#6b7280;text-transform:uppercase'>Cliente</th>")
            .append("<th style='text-align:left;padding:8px 12px;font-size:11px;font-weight:600;color:#6b7280;text-transform:uppercase'>Factura</th>")
            .append("<th style='text-align:right;padding:8px 12px;font-size:11px;font-weight:600;color:#6b7280;text-transform:uppercase'>DB</th>")
            .append("<th style='text-align:right;padding:8px 12px;font-size:11px;font-weight:600;color:#6b7280;text-transform:uppercase'>Stripe</th>")
            .append("<th style='text-align:right;padding:8px 12px;font-size:11px;font-weight:600;color:#6b7280;text-transform:uppercase'>Δ</th>")
            .append("<th style='text-align:right;padding:8px 12px;font-size:11px;font-weight:600;color:#6b7280;text-transform:uppercase'>Stripe link</th></tr>");
        for (PriceDiscrepancyService.Discrepancy r : rows) {
            String customer = (r.customerName() != null ? r.customerName() : "—")
                + (r.customerEmail() != null
                    ? "<br><span style='color:#6b7280;font-size:12px'>" + r.customerEmail() + "</span>"
                    : "");
            String facturaRef = r.idfactura() != null
                ? (r.cuenta() != null ? r.cuenta() : "PT") + r.idfactura()
                : "—";
            html.append("<tr style='border-bottom:1px solid #f5f5f5'>")
                .append("<td style='padding:10px 12px;vertical-align:top'>").append(customer).append("</td>")
                .append("<td style='padding:10px 12px;vertical-align:top;font-family:ui-monospace,SFMono-Regular,Menlo,monospace;font-size:11px;color:#6b7280'>")
                .append(facturaRef).append("</td>")
                .append("<td style='padding:10px 12px;vertical-align:top;text-align:right;white-space:nowrap;font-variant-numeric:tabular-nums;color:#111'>")
                .append(formatEur(r.dbAmount())).append("</td>")
                .append("<td style='padding:10px 12px;vertical-align:top;text-align:right;white-space:nowrap;font-variant-numeric:tabular-nums;font-weight:600;color:#111'>")
                .append(formatEur(r.stripeAmount())).append("</td>")
                .append("<td style='padding:10px 12px;vertical-align:top;text-align:right;white-space:nowrap;font-variant-numeric:tabular-nums;color:#dc2626;font-weight:600'>")
                .append(formatEur(r.delta())).append("</td>")
                .append("<td style='padding:10px 12px;vertical-align:top;text-align:right'>")
                .append("<a href='https://dashboard.stripe.com/invoices/").append(r.stripeInvoiceId())
                .append("' style='color:#2563eb;font-size:11px;text-decoration:none'>Open →</a></td></tr>");
        }
        html.append("</table>");

        html.append("</div>");
        html.append("<p style='margin:12px 0 0;font-size:11px;color:#9ca3af;text-align:center'>BeWorking · Price discrepancy audit</p>");
        html.append("</div>");

        try {
            emailService.sendHtml(ADMIN_EMAIL, subject, html.toString());
        } catch (Exception e) {
            logger.error("Failed to send price discrepancy report: {}", e.getMessage(), e);
        }
    }

    private static String formatEur(BigDecimal n) {
        if (n == null) return "€0.00";
        return String.format(Locale.ROOT, "€%,.2f", n);
    }

    public record RunResult(int discrepancies, boolean emailSent) {}
}
