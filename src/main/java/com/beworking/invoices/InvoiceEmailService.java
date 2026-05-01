package com.beworking.invoices;

import com.beworking.auth.EmailService;
import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Sends an invoice (with PDF attachment) to the contact's email and a CC
 * notification to info@be-working.com. Used by:
 *  - Admin "send invoice" button (InvoiceController)
 *  - Bank-transfer subscription creation (SubscriptionController)
 *  - Monthly cron / future automated paths
 */
@Service
public class InvoiceEmailService {

    private static final Logger LOGGER = LoggerFactory.getLogger(InvoiceEmailService.class);
    private static final String ADMIN_CC = "info@be-working.com";
    private static final String IBAN = "ES82 0182 2116 0102 0171 0670";
    private static final String IBAN_HOLDER = "BeWorking Partners Offices SL";

    private final InvoicePdfService pdfService;
    private final EmailService emailService;

    public InvoiceEmailService(InvoicePdfService pdfService, EmailService emailService) {
        this.pdfService = pdfService;
        this.emailService = emailService;
    }

    public static class Result {
        public final boolean clientEmailSent;
        public final String clientEmail;
        public final String invoiceNumber;
        public final String error;

        private Result(boolean sent, String email, String number, String error) {
            this.clientEmailSent = sent;
            this.clientEmail = email;
            this.invoiceNumber = number;
            this.error = error;
        }

        public static Result ok(String email, String number) {
            return new Result(true, email, number, null);
        }

        public static Result failure(String error) {
            return new Result(false, null, null, error);
        }
    }

    /**
     * Sends the invoice PDF to the contact and a CC notification to info@.
     * Both sends are best-effort; failures are logged. Returns metadata about
     * the primary (client) send.
     */
    public Result sendForInvoice(Long invoiceId) {
        try {
            String clientEmail = pdfService.getClientEmail(invoiceId);
            String clientName = pdfService.getClientName(invoiceId);
            String displayNumber = pdfService.getDisplayNumber(invoiceId);
            BigDecimal total = pdfService.getInvoiceTotal(invoiceId);

            if (clientEmail == null || clientEmail.isBlank()) {
                return Result.failure("No email found for this invoice's contact");
            }

            byte[] pdfBytes = pdfService.generatePdf(invoiceId);
            if (pdfBytes == null) {
                return Result.failure("PDF generation returned null");
            }

            NumberFormat currencyFormat = NumberFormat.getCurrencyInstance(new Locale("es", "ES"));
            currencyFormat.setMinimumFractionDigits(2);
            String totalStr = total != null ? currencyFormat.format(total) : "—";
            String safeName = clientName != null ? clientName : "Cliente";

            String clientHtml = "<div style='font-family:Arial,sans-serif;max-width:600px;margin:0 auto'>"
                + "<div style='background:#009624;padding:16px;text-align:center'>"
                + "<img src='https://app.be-working.com/beworking_logo.png' alt='BeWorking' style='height:40px' />"
                + "</div>"
                + "<div style='padding:24px'>"
                + "<p>Estimado/a " + safeName + ",</p>"
                + "<p>Adjuntamos su factura <strong>#" + displayNumber + "</strong> por importe de <strong>" + totalStr + "</strong>.</p>"
                + "<h3 style='color:#009624;margin-top:24px'>Datos para transferencia bancaria</h3>"
                + "<table style='border-collapse:collapse'>"
                + "<tr><td style='padding:4px 12px 4px 0;color:#666'>IBAN:</td><td><strong>" + IBAN + "</strong></td></tr>"
                + "<tr><td style='padding:4px 12px 4px 0;color:#666'>Titular:</td><td>" + IBAN_HOLDER + "</td></tr>"
                + "<tr><td style='padding:4px 12px 4px 0;color:#666'>Concepto:</td><td>Factura #" + displayNumber + "</td></tr>"
                + "</table>"
                + "<p style='margin-top:24px;color:#666;font-size:13px'>Gracias por ser parte de la comunidad BeWorking.</p>"
                + "</div></div>";

            String subject = "Factura #" + displayNumber + " - BeWorking";
            String attachmentName = "factura-" + displayNumber + ".pdf";

            emailService.sendHtmlWithAttachment(clientEmail, subject, clientHtml, pdfBytes, attachmentName);

            // CC notification to info@be-working.com — no attachment, just a record so
            // the admin inbox stays in sync with what was sent to the customer.
            try {
                String adminHtml = "<div style='font-family:Arial,sans-serif;max-width:600px;margin:0 auto'>"
                    + "<div style='padding:16px'>"
                    + "<p>Factura enviada al cliente:</p>"
                    + "<table style='border-collapse:collapse'>"
                    + "<tr><td style='padding:4px 12px 4px 0;color:#666'>Cliente:</td><td><strong>" + safeName + "</strong> (" + clientEmail + ")</td></tr>"
                    + "<tr><td style='padding:4px 12px 4px 0;color:#666'>Factura:</td><td>#" + displayNumber + "</td></tr>"
                    + "<tr><td style='padding:4px 12px 4px 0;color:#666'>Importe:</td><td>" + totalStr + "</td></tr>"
                    + "</table>"
                    + "</div></div>";
                emailService.sendHtml(ADMIN_CC, "Factura enviada — #" + displayNumber + " — " + safeName, adminHtml);
            } catch (Exception ccEx) {
                LOGGER.warn("Invoice {} sent to {} but admin CC to {} failed: {}",
                    displayNumber, clientEmail, ADMIN_CC, ccEx.getMessage());
            }

            return Result.ok(clientEmail, displayNumber);
        } catch (Exception e) {
            LOGGER.error("Failed to send invoice email for id {}: {}", invoiceId, e.getMessage(), e);
            return Result.failure(e.getMessage());
        }
    }
}
