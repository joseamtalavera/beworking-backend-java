package com.beworking.invoices;

import com.beworking.auth.EmailService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
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

@Component
public class DailyReconciliationScheduler {

    private static final Logger logger = LoggerFactory.getLogger(DailyReconciliationScheduler.class);
    private static final String ADMIN_EMAIL = "accounts@be-working.com";
    private static final String[] ACCOUNTS = {"GT", "PT"};

    private final JdbcTemplate jdbcTemplate;
    private final EmailService emailService;
    private final RestClient http;
    private final String paymentsBaseUrl;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public DailyReconciliationScheduler(JdbcTemplate jdbcTemplate,
                                        EmailService emailService,
                                        @Value("${app.payments.base-url:}") String paymentsBaseUrl) {
        this.jdbcTemplate = jdbcTemplate;
        this.emailService = emailService;
        this.paymentsBaseUrl = paymentsBaseUrl;
        this.http = RestClient.create();
    }

    @Scheduled(cron = "0 0 5 * * *")
    public void runDailyReconciliation() {
        logger.info("Daily reconciliation started");

        if (paymentsBaseUrl == null || paymentsBaseUrl.isBlank()) {
            logger.warn("Stripe payments base URL not configured — skipping reconciliation");
            return;
        }

        List<AccountResult> results = new ArrayList<>();
        boolean hasIssues = false;

        RuntimeException firstError = null;
        for (String account : ACCOUNTS) {
            try {
                AccountResult result = reconcileAccount(account);
                results.add(result);
                if (result.hasIssues()) {
                    hasIssues = true;
                }
                persist(result);
            } catch (Exception e) {
                logger.error("Reconciliation failed for account {}: {}", account, e.getMessage(), e);
                AccountResult errResult = new AccountResult(account);
                errResult.error = e.getMessage();
                results.add(errResult);
                hasIssues = true;
                if (firstError == null) {
                    firstError = new RuntimeException("[" + account + "] " + e.getMessage(), e);
                }
            }
        }
        if (firstError != null) {
            throw firstError;
        }

        if (hasIssues) {
            sendReport(results);
        } else {
            logger.info("Daily reconciliation completed — no issues found");
        }
    }

    private AccountResult reconcileAccount(String account) {
        AccountResult result = new AccountResult(account);

        // 1. DB active counts by billing method
        Integer dbStripe = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM beworking.subscriptions WHERE active = true AND cuenta = ? AND billing_method = 'stripe'",
            Integer.class, account);
        Integer dbBankTransfer = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM beworking.subscriptions WHERE active = true AND cuenta = ? AND billing_method = 'bank_transfer'",
            Integer.class, account);
        result.dbStripe = dbStripe != null ? dbStripe : 0;
        result.dbBankTransfer = dbBankTransfer != null ? dbBankTransfer : 0;
        result.dbActive = result.dbStripe + result.dbBankTransfer;

        // 2. Stripe data
        Map<String, Object> stripeData = http.get()
            .uri(paymentsBaseUrl + "/api/reconciliation/" + account)
            .retrieve()
            .body(new ParameterizedTypeReference<Map<String, Object>>() {});

        if (stripeData == null) throw new RuntimeException("Null response from stripe-service");

        result.stripeActive = ((Number) stripeData.get("stripeActive")).intValue();
        result.stripePastDue = ((Number) stripeData.get("stripePastDue")).intValue();
        result.pastDueAmount = new BigDecimal(stripeData.get("pastDueAmount").toString());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> pastDueSubs = (List<Map<String, Object>>) stripeData.get("pastDueSubs");
        result.pastDueSubs = pastDueSubs != null ? pastDueSubs : List.of();

        // 3. Find missing invoices: paid in Stripe but not in DB facturas
        @SuppressWarnings("unchecked")
        List<String> paidInvoiceIds = (List<String>) stripeData.get("paidInvoiceIds");
        if (paidInvoiceIds != null && !paidInvoiceIds.isEmpty()) {
            result.missingInvoices = findMissingInvoices(paidInvoiceIds, account);
        }

        // 4. Cross-check subscription IDs: DB vs Stripe
        @SuppressWarnings("unchecked")
        List<String> activeSubIds = (List<String>) stripeData.get("activeSubIds");
        List<String> pastDueSubIds = result.pastDueSubs.stream()
            .map(m -> (String) m.get("subscriptionId")).filter(id -> id != null).toList();

        if (activeSubIds != null) {
            java.util.Set<String> stripeIds = new java.util.HashSet<>(activeSubIds);
            stripeIds.addAll(pastDueSubIds);

            // DB Stripe subs (excluding scheduled and bank_transfer)
            List<String> dbSubIds = jdbcTemplate.queryForList(
                "SELECT stripe_subscription_id FROM beworking.subscriptions WHERE active = true AND cuenta = ? AND billing_method = 'stripe' AND stripe_subscription_id IS NOT NULL AND stripe_subscription_id != '' AND stripe_subscription_id NOT LIKE 'sub_sched_%'",
                String.class, account);
            java.util.Set<String> dbIds = new java.util.HashSet<>(dbSubIds);

            // In DB but not in Stripe (cancelled in Stripe but still active in DB)
            result.dbOnlySubIds = dbSubIds.stream().filter(id -> !stripeIds.contains(id)).toList();
            // In Stripe but not in DB (exists in Stripe but no DB record)
            result.stripeOnlySubIds = activeSubIds.stream().filter(id -> !dbIds.contains(id)).toList();
            result.stripeOnlySubIds.addAll(pastDueSubIds.stream().filter(id -> !dbIds.contains(id)).toList());
        }

        logger.info("Reconciliation [{}]: dbActive={} stripeActive={} pastDue={} missing={} dbOnly={} stripeOnly={}",
            account, result.dbActive, result.stripeActive, result.stripePastDue,
            result.missingInvoices.size(), result.dbOnlySubIds.size(), result.stripeOnlySubIds.size());

        return result;
    }

    private List<Map<String, Object>> findMissingInvoices(List<String> stripeInvoiceIds, String account) {
        List<Map<String, Object>> missing = new ArrayList<>();

        for (String invoiceId : stripeInvoiceIds) {
            try {
                Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM beworking.facturas WHERE stripeinvoiceid = ?",
                    Integer.class, invoiceId);
                if (count == null || count == 0) {
                    // Try to get client info from subscription
                    missing.add(Map.of("stripeInvoiceId", invoiceId));
                }
            } catch (EmptyResultDataAccessException ignored) {
                missing.add(Map.of("stripeInvoiceId", invoiceId));
            }
        }

        return missing;
    }

    private void persist(AccountResult result) {
        logger.info("Persisting reconciliation for {}: dbActive={} dbStripe={} dbBank={} stripeActive={}",
            result.account, result.dbActive, result.dbStripe, result.dbBankTransfer, result.stripeActive);
        try {
            String missingJson = objectMapper.writeValueAsString(result.missingInvoices);
            String pastDueJson = objectMapper.writeValueAsString(result.pastDueSubs);
            String dbOnlyJson = objectMapper.writeValueAsString(result.dbOnlySubIds);
            String stripeOnlyJson = objectMapper.writeValueAsString(result.stripeOnlySubIds);

            jdbcTemplate.update("""
                INSERT INTO beworking.reconciliation_results
                    (run_date, account, db_active, db_stripe, db_bank_transfer, stripe_active, stripe_past_due,
                     past_due_amount, missing_invoice_count, missing_invoices, past_due_subs,
                     db_only_subs, stripe_only_subs)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb, ?::jsonb)
                ON CONFLICT (run_date, account) DO UPDATE SET
                    db_active = EXCLUDED.db_active,
                    db_stripe = EXCLUDED.db_stripe,
                    db_bank_transfer = EXCLUDED.db_bank_transfer,
                    stripe_active = EXCLUDED.stripe_active,
                    stripe_past_due = EXCLUDED.stripe_past_due,
                    past_due_amount = EXCLUDED.past_due_amount,
                    missing_invoice_count = EXCLUDED.missing_invoice_count,
                    missing_invoices = EXCLUDED.missing_invoices,
                    past_due_subs = EXCLUDED.past_due_subs,
                    db_only_subs = EXCLUDED.db_only_subs,
                    stripe_only_subs = EXCLUDED.stripe_only_subs,
                    created_at = CURRENT_TIMESTAMP
                """,
                LocalDate.now(), result.account, result.dbActive, result.dbStripe, result.dbBankTransfer,
                result.stripeActive, result.stripePastDue, result.pastDueAmount, result.missingInvoices.size(),
                missingJson, pastDueJson, dbOnlyJson, stripeOnlyJson);

        } catch (Exception e) {
            logger.error("Failed to persist reconciliation result for {}: {}", result.account, e.getMessage(), e);
            throw new RuntimeException("persist(" + result.account + "): " + e.getMessage(), e);
        }
    }

    private void sendReport(List<AccountResult> results) {
        String subject = "⚠ BeWorking — Reconciliation Report " + LocalDate.now();

        StringBuilder html = new StringBuilder();
        html.append("<div style='font-family:Arial,Helvetica,sans-serif;max-width:640px;margin:0 auto;background:#fff;border-radius:12px;overflow:hidden;box-shadow:0 2px 12px rgba(0,0,0,0.08)'>");
        html.append("<div style='background:linear-gradient(135deg,#009624 0%,#00c853 100%);padding:32px;color:#fff'>");
        html.append("<p style='margin:0 0 4px;font-size:13px;letter-spacing:2px;text-transform:uppercase;opacity:0.85'>BEWORKING</p>");
        html.append("<h1 style='margin:0;font-size:22px;font-weight:700'>Daily Reconciliation — ").append(LocalDate.now()).append("</h1>");
        html.append("</div>");
        html.append("<div style='padding:24px 32px'>");

        for (AccountResult r : results) {
            boolean ok = !r.hasIssues();
            String color = ok ? "#009624" : (r.missingInvoices.size() > 0 ? "#d32f2f" : "#f57c00");
            String bg = ok ? "#f5faf6" : (r.missingInvoices.size() > 0 ? "#fef5f5" : "#fff8f0");

            html.append("<div style='margin-bottom:24px;background:").append(bg)
                .append(";border-radius:10px;padding:20px 24px;border-left:4px solid ").append(color).append("'>");
            html.append("<h2 style='margin:0 0 12px;font-size:16px;color:").append(color).append("'>")
                .append(r.account.equals("GT") ? "GLOBALTECHNO OÜ (GT)" : "BeWorking Partners (PT)").append("</h2>");
            html.append("<table style='border-collapse:collapse;width:100%'>");

            if (r.error != null) {
                html.append("<tr><td colspan='2' style='color:#d32f2f;font-size:13px'>Error: ").append(r.error).append("</td></tr>");
            } else {
                addRow(html, "DB active subscriptions", String.valueOf(r.dbActive));
                addRow(html, "Stripe active", String.valueOf(r.stripeActive));
                int diff = r.dbActive - r.stripeActive - r.stripePastDue;
                if (diff != 0) {
                    addRow(html, "⚠ Sync gap (DB vs Stripe live)", String.valueOf(diff), "#f57c00");
                }
                if (r.stripePastDue > 0) {
                    addRow(html, "⚠ Past due (payment failing)", r.stripePastDue + " subs — €" + r.pastDueAmount, "#f57c00");
                }
                if (!r.missingInvoices.isEmpty()) {
                    addRow(html, "✗ Missing invoices in DB", String.valueOf(r.missingInvoices.size()), "#d32f2f");
                }
                if (!r.hasIssues()) {
                    addRow(html, "Status", "All good ✓", "#009624");
                }
            }

            html.append("</table>");

            // Past due detail
            if (!r.pastDueSubs.isEmpty()) {
                html.append("<p style='margin:16px 0 6px;font-size:13px;font-weight:700;color:#f57c00'>Past due subscriptions:</p>");
                html.append("<table style='border-collapse:collapse;width:100%;font-size:12px'>");
                html.append("<tr style='background:#f5f5f5'><th style='text-align:left;padding:6px 8px'>Stripe Sub ID</th><th style='text-align:right;padding:6px 8px'>Amount Due</th></tr>");
                for (Map<String, Object> sub : r.pastDueSubs) {
                    html.append("<tr><td style='padding:5px 8px;border-bottom:1px solid #eee'>").append(sub.get("subscriptionId")).append("</td>");
                    html.append("<td style='padding:5px 8px;border-bottom:1px solid #eee;text-align:right'>€").append(sub.get("amountDue")).append("</td></tr>");
                }
                html.append("</table>");
            }

            // Missing invoice detail
            if (!r.missingInvoices.isEmpty()) {
                html.append("<p style='margin:16px 0 6px;font-size:13px;font-weight:700;color:#d32f2f'>Missing invoices (paid in Stripe, not in DB):</p>");
                html.append("<table style='border-collapse:collapse;width:100%;font-size:12px'>");
                html.append("<tr style='background:#f5f5f5'><th style='text-align:left;padding:6px 8px'>Stripe Invoice ID</th></tr>");
                for (Map<String, Object> inv : r.missingInvoices) {
                    html.append("<tr><td style='padding:5px 8px;border-bottom:1px solid #eee'>").append(inv.get("stripeInvoiceId")).append("</td></tr>");
                }
                html.append("</table>");
            }

            html.append("</div>");
        }

        html.append("</div>");
        html.append("<div style='background:#f9f9f9;padding:12px 32px;text-align:center;border-top:1px solid #eee'>");
        html.append("<p style='margin:0;font-size:12px;color:#aaa'>© BeWorking · Daily Reconciliation System</p>");
        html.append("</div></div>");

        try {
            emailService.sendHtml(ADMIN_EMAIL, subject, html.toString());
            logger.info("Reconciliation report sent to {}", ADMIN_EMAIL);
        } catch (Exception e) {
            logger.error("Failed to send reconciliation report: {}", e.getMessage(), e);
        }
    }

    private void addRow(StringBuilder html, String label, String value) {
        addRow(html, label, value, "#333");
    }

    private void addRow(StringBuilder html, String label, String value, String valueColor) {
        html.append("<tr>")
            .append("<td style='padding:5px 12px 5px 0;color:#888;font-size:13px'>").append(label).append("</td>")
            .append("<td style='padding:5px 0;font-size:14px;font-weight:600;color:").append(valueColor).append("'>").append(value).append("</td>")
            .append("</tr>");
    }

    static class AccountResult {
        String account;
        int dbActive;
        int dbStripe;
        int dbBankTransfer;
        int stripeActive;
        int stripePastDue;
        BigDecimal pastDueAmount = BigDecimal.ZERO;
        List<Map<String, Object>> pastDueSubs = new ArrayList<>();
        List<Map<String, Object>> missingInvoices = new ArrayList<>();
        List<String> dbOnlySubIds = new ArrayList<>();
        List<String> stripeOnlySubIds = new ArrayList<>();
        String error;

        AccountResult(String account) {
            this.account = account;
        }

        boolean hasIssues() {
            return error != null
                || !missingInvoices.isEmpty()
                || stripePastDue > 0
                || !dbOnlySubIds.isEmpty()
                || !stripeOnlySubIds.isEmpty();
        }
    }
}
