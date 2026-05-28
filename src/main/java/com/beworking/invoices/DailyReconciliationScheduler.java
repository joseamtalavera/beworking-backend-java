package com.beworking.invoices;

import com.beworking.auth.EmailService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
    private static final String ADMIN_EMAIL = "info@be-working.com";
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
          runOnce();
      }
  
      public RunResult runOnce() {
          logger.info("Daily reconciliation started");
  
          if (paymentsBaseUrl == null || paymentsBaseUrl.isBlank()) {
              logger.warn("Stripe payments base URL not configured — skipping reconciliation");
              return new RunResult(0, 0, 0, 0, false);
          }
  
          List<AccountResult> results = new ArrayList<>();
          boolean hasIssues = false;
          RuntimeException firstError = null;
  
          for (String account : ACCOUNTS) {
              try {
                  AccountResult result = reconcileAccount(account);
                  results.add(result);
                  if (result.hasIssues()) hasIssues = true;
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
          if (firstError != null) throw firstError;
  
          boolean anySubIssues     = results.stream().anyMatch(AccountResult::hasSubIssues);
          boolean anyInvoiceIssues = results.stream().anyMatch(AccountResult::hasInvoiceIssues);
          if (anySubIssues)     sendSubscriptionReport(results);
          if (anyInvoiceIssues) sendInvoiceReport(results);
          if (!anySubIssues && !anyInvoiceIssues) {
              logger.info("Daily reconciliation completed — no issues found");
          }
  
          int totalMissing = results.stream().mapToInt(r -> r.missingInvoices.size()).sum();
          int totalPastDue = results.stream().mapToInt(r -> r.stripePastDue).sum();
          int totalDeviation = results.stream().mapToInt(AccountResult::deviation).sum();
          return new RunResult(results.size(), totalMissing, totalPastDue, totalDeviation, hasIssues);
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
        Integer dbScheduled = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM beworking.subscriptions " +
            "WHERE active = true AND cuenta = ? AND billing_method = 'stripe' " +
            "  AND stripe_subscription_id LIKE 'sub_sched_%'",
            Integer.class, account);
        result.dbStripe = dbStripe != null ? dbStripe : 0;
        result.dbBankTransfer = dbBankTransfer != null ? dbBankTransfer : 0;
        result.dbScheduled = dbScheduled != null ? dbScheduled : 0;
        result.dbActive = result.dbStripe + result.dbBankTransfer;

        // 1b. Pendiente invoices for current year. Restricted to subscription
        //     categories (`virtual_office`, `coworking`) so meeting-room one-offs
        //     and extras don't inflate the sub-reconciliation count. Persisted
        //     as a JSONB list so the dashboard renders the exact same rows the
        //     email summarises — no client-side filtering, no drift.
        result.pendingInvoices = jdbcTemplate.queryForList(
            "SELECT f.id, f.idfactura, " +
            "       UPPER(COALESCE(NULLIF(f.holdedcuenta, ''), 'PT')) AS cuenta, " +
            "       COALESCE(NULLIF(f.billing_name, ''), f.descripcion) AS \"clientName\", " +
            "       f.estado, " +
            "       f.creacionfecha AS \"fechaFactura\", " +
            "       f.total, " +
            "       f.stripeinvoiceid AS \"stripeInvoiceId\" " +
            "  FROM beworking.facturas f " +
            " WHERE EXTRACT(YEAR FROM f.creacionfecha) = EXTRACT(YEAR FROM CURRENT_DATE) " +
            "   AND UPPER(COALESCE(NULLIF(f.holdedcuenta, ''), 'PT')) = ? " +
            "   AND LOWER(COALESCE(f.category, '')) IN ('virtual_office', 'coworking') " +
            "   AND (LOWER(COALESCE(f.estado,'')) LIKE '%pend%' " +
            "     OR LOWER(COALESCE(f.estado,'')) LIKE '%confir%' " +
            "     OR LOWER(COALESCE(f.estado,'')) LIKE '%fact%' " +
            "     OR LOWER(COALESCE(f.estado,'')) LIKE '%invoice%' " +
            "     OR LOWER(COALESCE(f.estado,'')) LIKE '%created%') " +
            " ORDER BY f.creacionfecha DESC, f.id DESC",
            account);
        result.pendienteCount = result.pendingInvoices.size();
        BigDecimal sum = BigDecimal.ZERO;
        for (Map<String, Object> row : result.pendingInvoices) {
            Object t = row.get("total");
            if (t != null) sum = sum.add(new BigDecimal(t.toString()));
        }
        result.pendienteAmount = sum;

        // 2. Stripe data
        Map<String, Object> stripeData = http.get()
            .uri(paymentsBaseUrl + "/api/reconciliation/" + account)
            .retrieve()
            .body(new ParameterizedTypeReference<Map<String, Object>>() {});

        if (stripeData == null) throw new RuntimeException("Null response from stripe-service");
        logger.info("Stripe reconciliation payload [{}] keys: {}", account, stripeData.keySet());

        Object sa = stripeData.get("stripeActive");
        Object spd = stripeData.get("stripePastDue");
        Object pda = stripeData.get("pastDueAmount");
        if (sa == null || spd == null) {
            throw new RuntimeException("stripe-service missing required fields for " + account
                + " (stripeActive=" + sa + ", stripePastDue=" + spd + ", keys=" + stripeData.keySet() + ")");
        }
        result.stripeActive = ((Number) sa).intValue();
        result.stripePastDue = ((Number) spd).intValue();
        result.pastDueAmount = pda != null ? new BigDecimal(pda.toString()) : BigDecimal.ZERO;

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> pastDueSubs = (List<Map<String, Object>>) stripeData.get("pastDueSubs");
        result.pastDueSubs = pastDueSubs != null ? pastDueSubs : List.of();

        // 3. Find missing invoices: paid in Stripe but not in DB facturas
        @SuppressWarnings("unchecked")
        List<String> paidInvoiceIds = (List<String>) stripeData.get("paidInvoiceIds");
        if (paidInvoiceIds != null && !paidInvoiceIds.isEmpty()) {
            result.missingInvoices = findMissingInvoices(paidInvoiceIds, account);
        }

        // 3b. Enrich past-due subs with customer name + email from DB lookup
        result.pastDueSubs = enrichWithCustomer(result.pastDueSubs);

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
            result.dbOnlySubIds = new ArrayList<>(dbSubIds.stream().filter(id -> !stripeIds.contains(id)).toList());
            // In Stripe but not in DB (exists in Stripe but no DB record)
            result.stripeOnlySubIds = new ArrayList<>(activeSubIds.stream().filter(id -> !dbIds.contains(id)).toList());
            result.stripeOnlySubIds.addAll(pastDueSubIds.stream().filter(id -> !dbIds.contains(id)).toList());
        }

        // 5. Enrich dbOnlySubIds with customer info + Stripe cancelled_at — both the
        //    daily email and dashboard render from this enriched list, no live calls.
        result.dbOnlySubs = enrichDbOnlySubs(result.dbOnlySubIds);

        // 6. Direction (b) of Invoice Deviation: Stripe says paid, DB factura is
        //    still Pendiente (webhook landed late, sub cancelled in the gap, etc).
        if (paidInvoiceIds != null && !paidInvoiceIds.isEmpty()) {
            result.stripePaidDbPending = findStripePaidDbPending(paidInvoiceIds, account);
        }

        logger.info("Reconciliation [{}]: dbActive={} stripeActive={} pastDue={} missing={} dbOnly={} stripeOnly={} stripePaidDbPending={}",
            account, result.dbActive, result.stripeActive, result.stripePastDue,
            result.missingInvoices.size(), result.dbOnlySubIds.size(), result.stripeOnlySubIds.size(),
            result.stripePaidDbPending.size());

        return result;
    }

    private List<Map<String, Object>> enrichDbOnlySubs(List<String> subIds) {
        if (subIds == null || subIds.isEmpty()) return new ArrayList<>();
        List<Map<String, Object>> out = new ArrayList<>(subIds.size());
        for (String subId : subIds) {
            Map<String, Object> row = new HashMap<>();
            row.put("subscriptionId", subId);
            try {
                Map<String, Object> info = jdbcTemplate.queryForMap(
                    "SELECT cp.name AS customer_name, " +
                    "       cp.email_primary AS customer_email, " +
                    "       cp.phone_primary AS customer_phone, " +
                    "       s.monthly_amount, " +
                    "       s.billing_interval, " +
                    "       s.start_date, " +
                    "       s.end_date " +
                    "  FROM beworking.subscriptions s " +
                    "  JOIN beworking.contact_profiles cp ON cp.id = s.contact_id " +
                    " WHERE s.stripe_subscription_id = ? LIMIT 1",
                    subId);
                row.put("customerName",   info.get("customer_name"));
                row.put("customerEmail",  info.get("customer_email"));
                row.put("customerPhone",  info.get("customer_phone"));
                row.put("monthlyAmount",  info.get("monthly_amount"));
                row.put("billingInterval", info.get("billing_interval"));
                row.put("startDate",      info.get("start_date"));
                row.put("cancelledAt",    info.get("end_date"));
            } catch (EmptyResultDataAccessException ignored) {
                // Sub ID in DB list but DB row was hard-deleted in the meantime — keep just the ID.
            }
            out.add(row);
        }
        return out;
    }

    private List<Map<String, Object>> findStripePaidDbPending(List<String> stripeInvoiceIds, String account) {
        if (stripeInvoiceIds == null || stripeInvoiceIds.isEmpty()) return new ArrayList<>();
        List<Map<String, Object>> out = new ArrayList<>();
        Set<String> ignored = loadIgnoredInvoiceIds();
        for (String invoiceId : stripeInvoiceIds) {
            if (ignored.contains(invoiceId)) continue;
            try {
                Map<String, Object> row = jdbcTemplate.queryForMap(
                    "SELECT f.id, f.idfactura, " +
                    "       UPPER(COALESCE(NULLIF(f.holdedcuenta, ''), 'PT')) AS cuenta, " +
                    "       COALESCE(NULLIF(f.billing_name, ''), f.descripcion) AS \"clientName\", " +
                    "       f.estado, f.creacionfecha AS \"fechaFactura\", f.total, " +
                    "       f.stripeinvoiceid AS \"stripeInvoiceId\" " +
                    "  FROM beworking.facturas f " +
                    " WHERE f.stripeinvoiceid = ? " +
                    "   AND UPPER(COALESCE(NULLIF(f.holdedcuenta, ''), 'PT')) = ? " +
                    "   AND LOWER(COALESCE(f.estado, '')) LIKE '%pend%' " +
                    " LIMIT 1",
                    invoiceId, account);
                out.add(row);
            } catch (EmptyResultDataAccessException ignored2) {
                // Either no DB row (it's a missingInvoice — already tracked) or it's not Pendiente.
            }
        }
        return out;
    }

    private List<Map<String, Object>> findMissingInvoices(List<String> stripeInvoiceIds, String account) {
        List<Map<String, Object>> missing = new ArrayList<>();
        Set<String> ignored = loadIgnoredInvoiceIds();

        for (String invoiceId : stripeInvoiceIds) {
            if (ignored.contains(invoiceId)) continue;
            try {
                Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM beworking.facturas WHERE stripeinvoiceid = ?",
                    Integer.class, invoiceId);
                if (count == null || count == 0) {
                    missing.add(buildMissingInvoiceEntry(invoiceId));
                }
            } catch (EmptyResultDataAccessException ignored2) {
                missing.add(buildMissingInvoiceEntry(invoiceId));
            }
        }

        return missing;
    }

    private Set<String> loadIgnoredInvoiceIds() {
        try {
            return new HashSet<>(jdbcTemplate.queryForList(
                "SELECT stripe_invoice_id FROM beworking.reconciliation_ignored_invoices",
                String.class));
        } catch (Exception e) {
            logger.warn("Could not read reconciliation_ignored_invoices: {}", e.getMessage());
            return new HashSet<>();
        }
    }

    private Map<String, Object> buildMissingInvoiceEntry(String invoiceId) {
        Map<String, Object> entry = new HashMap<>();
        entry.put("stripeInvoiceId", invoiceId);
        entry.put("stripeUrl", "https://dashboard.stripe.com/invoices/" + invoiceId);
        return entry;
    }

    private List<Map<String, Object>> enrichWithCustomer(List<Map<String, Object>> subs) {
        if (subs == null || subs.isEmpty()) return subs;
        List<Map<String, Object>> enriched = new ArrayList<>(subs.size());
        for (Map<String, Object> sub : subs) {
            Map<String, Object> mutable = new HashMap<>(sub);
            String subId = (String) sub.get("subscriptionId");
            if (subId != null) {
                try {
                    Map<String, Object> contact = jdbcTemplate.queryForMap(
                        "SELECT cp.name, cp.email_primary AS email, cp.phone_primary AS phone " +
                        "FROM beworking.subscriptions s " +
                        "JOIN beworking.contact_profiles cp ON cp.id = s.contact_id " +
                        "WHERE s.stripe_subscription_id = ? LIMIT 1",
                        subId);
                    mutable.put("customerName", contact.get("name"));
                    mutable.put("customerEmail", contact.get("email"));
                    mutable.put("customerPhone", contact.get("phone"));
                } catch (EmptyResultDataAccessException ignored) {
                    // No DB match — sub exists in Stripe but not locally. Already
                    // surfaced as stripeOnlySubIds; no customer to show here.
                }
            }
            enriched.add(mutable);
        }
        return enriched;
    }

    private static String formatEur(Object amount) {
        if (amount == null) return "€0.00";
        try {
            BigDecimal n = (amount instanceof BigDecimal)
                ? (BigDecimal) amount
                : new BigDecimal(amount.toString());
            return String.format(Locale.ROOT, "€%,.2f", n);
        } catch (Exception e) {
            return "€" + amount;
        }
    }

    private void persist(AccountResult result) {
        logger.info("Persisting reconciliation for {}: dbActive={} dbStripe={} dbBank={} stripeActive={}",
            result.account, result.dbActive, result.dbStripe, result.dbBankTransfer, result.stripeActive);
        try {
            String missingJson = objectMapper.writeValueAsString(result.missingInvoices);
            String pastDueJson = objectMapper.writeValueAsString(result.pastDueSubs);
            // db_only_subs JSONB now holds the enriched objects (customer + cancelledAt
            // + monthlyAmount). Old rows that were plain string arrays get overwritten
            // on the next run — daily cron rotates the snapshot.
            String dbOnlyJson = objectMapper.writeValueAsString(
                result.dbOnlySubs != null && !result.dbOnlySubs.isEmpty()
                    ? result.dbOnlySubs
                    : result.dbOnlySubIds);
            String stripeOnlyJson = objectMapper.writeValueAsString(result.stripeOnlySubIds);
            String pendingJson = objectMapper.writeValueAsString(result.pendingInvoices);
            String stripePaidDbPendingJson = objectMapper.writeValueAsString(result.stripePaidDbPending);

            jdbcTemplate.update("""
                INSERT INTO beworking.reconciliation_results
                    (run_date, account, db_active, db_stripe, db_bank_transfer, stripe_active, stripe_past_due,
                     past_due_amount, missing_invoice_count, missing_invoices, past_due_subs,
                     db_only_subs, stripe_only_subs, pendiente_count, pendiente_amount, pending_invoices,
                     stripe_paid_db_pending)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb, ?::jsonb, ?, ?, ?::jsonb, ?::jsonb)
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
                    pendiente_count = EXCLUDED.pendiente_count,
                    pendiente_amount = EXCLUDED.pendiente_amount,
                    pending_invoices = EXCLUDED.pending_invoices,
                    stripe_paid_db_pending = EXCLUDED.stripe_paid_db_pending,
                    created_at = CURRENT_TIMESTAMP
                """,
                LocalDate.now(), result.account, result.dbActive, result.dbStripe, result.dbBankTransfer,
                result.stripeActive, result.stripePastDue, result.pastDueAmount, result.missingInvoices.size(),
                missingJson, pastDueJson, dbOnlyJson, stripeOnlyJson,
                result.pendienteCount, result.pendienteAmount, pendingJson, stripePaidDbPendingJson);

        } catch (Exception e) {
            logger.error("Failed to persist reconciliation result for {}: {}", result.account, e.getMessage(), e);
            throw new RuntimeException("persist(" + result.account + "): " + e.getMessage(), e);
        }
    }

    private void sendSubscriptionReport(List<AccountResult> results) {
        sendReport(
            "Subscription Reconciliation",
            "BeWorking — Subscription Reconciliation " + LocalDate.now(),
            results,
            ReportKind.SUBSCRIPTION);
    }

    private void sendInvoiceReport(List<AccountResult> results) {
        sendReport(
            "Invoice Reconciliation",
            "BeWorking — Invoice Reconciliation " + LocalDate.now(),
            results,
            ReportKind.INVOICE);
    }

    private enum ReportKind { SUBSCRIPTION, INVOICE }

    private void sendReport(String title, String subject, List<AccountResult> results, ReportKind kind) {
        String fontStack = "-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Helvetica,Arial,sans-serif";

        StringBuilder html = new StringBuilder();
        html.append("<div style=\"font-family:").append(fontStack).append(";max-width:720px;margin:24px auto;color:#1a1a1a;background:#fafafa;padding:24px\">");
        html.append("<div style='background:#fff;border:1px solid #e5e7eb;border-radius:12px;padding:28px 32px'>");

        // Header: 2-cell table (Gmail/Outlook strip flex layout).
        html.append("<table style='width:100%;border-collapse:collapse;margin-bottom:24px'>");
        html.append("<tr>");
        html.append("<td style='padding:0 16px 16px 0;text-align:left;border-bottom:1px solid #f0f0f0'>");
        html.append("<span style='font-size:18px;font-weight:600;letter-spacing:-0.015em;color:#111'>").append(title).append("</span>");
        html.append("</td>");
        html.append("<td style='padding:0 0 16px 16px;text-align:right;border-bottom:1px solid #f0f0f0;white-space:nowrap;vertical-align:baseline'>");
        html.append("<span style='font-size:12px;color:#6b7280'>Last run: ").append(LocalDate.now()).append("</span>");
        html.append("</td>");
        html.append("</tr>");
        html.append("</table>");

        for (AccountResult r : results) {
            boolean kindHasIssues = kind == ReportKind.SUBSCRIPTION ? r.hasSubIssues() : r.hasInvoiceIssues();
            if (!kindHasIssues && r.error == null) continue;
            boolean ok = !kindHasIssues;
            boolean hasAlert = kind == ReportKind.INVOICE
                && (!r.missingInvoices.isEmpty() || !r.stripePaidDbPending.isEmpty());
            String accent = ok ? "#16a34a" : (hasAlert ? "#dc2626" : "#ea580c");
            String accentBg = ok ? "#f0fdf4" : (hasAlert ? "#fef2f2" : "#fff7ed");
            String statusLabel = r.error != null ? "Error" : (ok ? "OK" : (hasAlert ? "Alert" : "Warning"));
            String displayName = r.account.equals("GT") ? "GT" : "PT";
            String fullName = r.account.equals("GT") ? "GLOBALTECHNO OÜ" : "BeWorking Partners";

            html.append("<div style='border:1px solid #e5e7eb;border-radius:10px;overflow:hidden;margin-bottom:20px'>");

            // Card header
            html.append("<table style='width:100%;border-collapse:collapse;background:").append(accentBg).append(";border-bottom:1px solid #e5e7eb'>");
            html.append("<tr>");
            html.append("<td style='padding:12px 16px;vertical-align:middle'>");
            html.append("<span style='display:inline-block;width:8px;height:8px;border-radius:50%;background:").append(accent).append(";margin-right:8px;vertical-align:middle'></span>");
            html.append("<span style='font-size:14px;font-weight:700;color:#111;vertical-align:middle'>").append(displayName).append("</span>");
            html.append("<span style='font-size:13px;color:#6b7280;margin-left:4px;vertical-align:middle'> · ").append(fullName).append("</span>");
            html.append("</td>");
            html.append("<td style='padding:12px 16px;vertical-align:middle;text-align:right'>");
            html.append("<span style='font-size:11px;font-weight:600;background:#fff;border:1px solid ").append(accent).append(";color:").append(accent).append(";padding:3px 10px;border-radius:12px'>").append(statusLabel).append("</span>");
            html.append("</td>");
            html.append("</tr>");
            html.append("</table>");

            // Body
            html.append("<div style='padding:18px 16px;background:#fff'>");
            if (r.error != null) {
                html.append("<p style='color:#dc2626;font-size:13px;margin:0'>Error: ").append(r.error).append("</p>");
            } else if (kind == ReportKind.SUBSCRIPTION) {
                renderSubscriptionBody(html, r);
            } else {
                renderInvoiceBody(html, r);
            }
            html.append("</div>"); // body
            html.append("</div>"); // card
        }

        html.append("</div>"); // outer card
        html.append("<p style='margin:16px 0 0;font-size:11px;color:#9ca3af;text-align:center'>BeWorking · Daily Reconciliation</p>");
        html.append("</div>");

        try {
            emailService.sendHtml(ADMIN_EMAIL, subject, html.toString());
            logger.info("{} report sent to {}", title, ADMIN_EMAIL);
        } catch (Exception e) {
            logger.error("Failed to send {}: {}", title, e.getMessage(), e);
        }
    }

    private void renderSubscriptionBody(StringBuilder html, AccountResult r) {
        // 5-metric grid: DB / Stripe / Bank / Scheduled / Deviation
        html.append("<table style='border-collapse:collapse;width:100%;table-layout:fixed'>");
        html.append("<tr>");
        addMetric(html, "DB",         String.valueOf(r.dbActive),          null, null);
        addMetric(html, "Stripe",     String.valueOf(r.stripeLive()),      null, null);
        addMetric(html, "Bank",       String.valueOf(r.dbBankTransfer),    null, null);
        addMetric(html, "Scheduled",  String.valueOf(r.dbScheduled),       null, null);
        addMetric(html, "Deviation",  String.valueOf(r.deviation()),       null,
            r.deviation() > 0 ? "#dc2626" : null);
        html.append("</tr></table>");

        // Below each section: ID list (no fancy table in email — IDs only).
        if (!r.dbOnlySubs.isEmpty() || !r.dbOnlySubIds.isEmpty()) {
            html.append(sectionTitle("Deviation — DB active, Stripe cancelled", "#ea580c"));
            renderSubIdList(html, r);
        }
        if (!r.stripeOnlySubIds.isEmpty()) {
            html.append(sectionTitle("Stripe-only subs", "#ea580c"));
            html.append("<p style='margin:0 0 8px;font-size:12px;color:#6b7280'>Active in Stripe, no record in DB.</p>");
            html.append("<div style='font-family:ui-monospace,SFMono-Regular,Menlo,monospace;font-size:11px;color:#374151;line-height:1.8;background:#fafafa;border:1px solid #f0f0f0;border-radius:6px;padding:10px 12px'>");
            for (String id : r.stripeOnlySubIds) html.append(id).append("<br>");
            html.append("</div>");
        }
    }

    @SuppressWarnings("unchecked")
    private void renderSubIdList(StringBuilder html, AccountResult r) {
        html.append("<p style='margin:0 0 8px;font-size:12px;color:#6b7280'>Cancelled in Stripe, still active in DB.</p>");
        html.append("<div style='font-family:ui-monospace,SFMono-Regular,Menlo,monospace;font-size:11px;color:#374151;line-height:1.8;background:#fafafa;border:1px solid #f0f0f0;border-radius:6px;padding:10px 12px'>");
        if (!r.dbOnlySubs.isEmpty()) {
            for (Map<String, Object> sub : r.dbOnlySubs) {
                String subId = String.valueOf(sub.get("subscriptionId"));
                String name = sub.get("customerName") != null ? String.valueOf(sub.get("customerName")) : "—";
                html.append(subId).append(" &nbsp;·&nbsp; <span style='color:#6b7280'>").append(name).append("</span><br>");
            }
        } else {
            for (String id : r.dbOnlySubIds) html.append(id).append("<br>");
        }
        html.append("</div>");
    }

    private void renderInvoiceBody(StringBuilder html, AccountResult r) {
        // 3-metric grid: Overdue / Unpaid / Deviation
        int devCount = r.missingInvoices.size() + r.stripePaidDbPending.size();
        html.append("<table style='border-collapse:collapse;width:100%;table-layout:fixed'>");
        html.append("<tr>");
        addMetric(html, "Overdue",   String.valueOf(r.stripePastDue),
            r.stripePastDue > 0 ? formatEur(r.pastDueAmount) : null,
            r.stripePastDue > 0 ? "#dc2626" : null);
        addMetric(html, "Unpaid",    String.valueOf(r.pendienteCount),
            r.pendienteCount > 0 ? formatEur(r.pendienteAmount) : null,
            r.pendienteCount > 0 ? "#dc2626" : null);
        addMetric(html, "Deviation", String.valueOf(devCount), null,
            devCount > 0 ? "#dc2626" : null);
        html.append("</tr></table>");

        // Below each metric: relevant IDs.
        if (!r.pastDueSubs.isEmpty()) {
            html.append(sectionTitle("Overdue — Stripe past_due", "#ea580c"));
            html.append("<div style='font-family:ui-monospace,SFMono-Regular,Menlo,monospace;font-size:11px;color:#374151;line-height:1.8;background:#fafafa;border:1px solid #f0f0f0;border-radius:6px;padding:10px 12px'>");
            for (Map<String, Object> sub : r.pastDueSubs) {
                String subId = String.valueOf(sub.get("subscriptionId"));
                String invId = sub.get("latestInvoiceId") != null ? String.valueOf(sub.get("latestInvoiceId")) : "—";
                String name = sub.get("customerName") != null ? String.valueOf(sub.get("customerName")) : "—";
                html.append(invId).append(" &nbsp;·&nbsp; <span style='color:#6b7280'>").append(name).append("</span> &nbsp;·&nbsp; ").append(subId).append("<br>");
            }
            html.append("</div>");
        }

        if (!r.pendingInvoices.isEmpty()) {
            html.append(sectionTitle("Unpaid — DB Pendiente (subs)", "#ea580c"));
            html.append("<div style='font-family:ui-monospace,SFMono-Regular,Menlo,monospace;font-size:11px;color:#374151;line-height:1.8;background:#fafafa;border:1px solid #f0f0f0;border-radius:6px;padding:10px 12px'>");
            for (Map<String, Object> inv : r.pendingInvoices) {
                Object num = inv.get("idfactura");
                Object cuenta = inv.get("cuenta");
                Object name = inv.get("clientName");
                Object total = inv.get("total");
                Object stripeId = inv.get("stripeInvoiceId");
                html.append(cuenta != null ? cuenta : "").append(num != null ? num : "")
                    .append(" &nbsp;·&nbsp; <span style='color:#6b7280'>").append(name != null ? name : "—").append("</span>")
                    .append(" &nbsp;·&nbsp; ").append(formatEur(total));
                if (stripeId != null && !String.valueOf(stripeId).isBlank()) {
                    html.append(" &nbsp;·&nbsp; <span style='color:#9ca3af'>").append(stripeId).append("</span>");
                }
                html.append("<br>");
            }
            html.append("</div>");
        }

        if (!r.missingInvoices.isEmpty() || !r.stripePaidDbPending.isEmpty()) {
            html.append(sectionTitle("Deviation — Stripe vs DB", "#dc2626"));
            html.append("<div style='font-family:ui-monospace,SFMono-Regular,Menlo,monospace;font-size:11px;color:#374151;line-height:1.8;background:#fafafa;border:1px solid #f0f0f0;border-radius:6px;padding:10px 12px'>");
            for (Map<String, Object> inv : r.missingInvoices) {
                String invId = String.valueOf(inv.get("stripeInvoiceId"));
                html.append(invId).append(" &nbsp;·&nbsp; <span style='color:#6b7280'>paid in Stripe, missing in DB</span><br>");
            }
            for (Map<String, Object> inv : r.stripePaidDbPending) {
                String invId = String.valueOf(inv.get("stripeInvoiceId"));
                Object name = inv.get("clientName");
                html.append(invId).append(" &nbsp;·&nbsp; <span style='color:#6b7280'>").append(name != null ? name : "—").append(" — DB still Pendiente</span><br>");
            }
            html.append("</div>");
        }
    }

    private static String sectionTitle(String text, String color) {
        return "<h3 style='margin:24px 0 8px;font-size:13px;font-weight:600;color:" + color
            + ";letter-spacing:-0.005em'>" + text + "</h3>";
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

    private void addMetric(StringBuilder html, String label, String value, String sub, String valueColor) {
        String valueCol = valueColor != null ? valueColor : "#111";
        String labelCol = valueColor != null ? valueColor : "#6b7280";
        html.append("<td style='text-align:left;padding:8px 4px;vertical-align:top'>");
        html.append("<div style='font-size:10px;letter-spacing:0.08em;text-transform:uppercase;color:").append(labelCol)
            .append(";font-weight:600;margin-bottom:6px'>")
            .append(label).append("</div>");
        html.append("<div style='font-size:22px;font-weight:700;color:").append(valueCol)
            .append(";line-height:1;font-variant-numeric:tabular-nums'>")
            .append(value).append("</div>");
        if (sub != null) {
            html.append("<div style='font-size:11px;color:").append(valueCol)
                .append(";opacity:0.85;margin-top:4px;font-variant-numeric:tabular-nums'>")
                .append(sub).append("</div>");
        }
        html.append("</td>");
    }

    static class AccountResult {
        String account;
        int dbActive;
        int dbStripe;
        int dbBankTransfer;
        int dbScheduled;
        int stripeActive;
        int stripePastDue;
        BigDecimal pastDueAmount = BigDecimal.ZERO;
        int pendienteCount;
        BigDecimal pendienteAmount = BigDecimal.ZERO;
        List<Map<String, Object>> pendingInvoices = new ArrayList<>();
        List<Map<String, Object>> pastDueSubs = new ArrayList<>();
        List<Map<String, Object>> missingInvoices = new ArrayList<>();
        List<Map<String, Object>> stripePaidDbPending = new ArrayList<>();
        List<String> dbOnlySubIds = new ArrayList<>();
        List<Map<String, Object>> dbOnlySubs = new ArrayList<>();
        List<String> stripeOnlySubIds = new ArrayList<>();
        String error;

        AccountResult(String account) {
            this.account = account;
        }

        // Mirror of dashboard ReconciliationCard.metrics():
        //   stripe   = stripeActive + stripePastDue (live in Stripe)
        //   scheduled = dbScheduled (sub_sched_*)
        //   bank     = dbBankTransfer
        //   deviation = max(0, dbActive - stripe - scheduled - bank) — ghost subs
        //   total    = stripe + scheduled + deviation + bank
        int stripeLive() { return stripeActive + stripePastDue; }
        int deviation() { return Math.max(0, dbActive - stripeLive() - dbScheduled - dbBankTransfer); }
        int total() { return stripeLive() + dbScheduled + deviation() + dbBankTransfer; }

        boolean hasSubIssues() {
            return error != null
                || deviation() > 0
                || !stripeOnlySubIds.isEmpty();
        }

        boolean hasInvoiceIssues() {
            return error != null
                || !missingInvoices.isEmpty()
                || !stripePaidDbPending.isEmpty()
                || stripePastDue > 0
                || pendienteCount > 0;
        }

        boolean hasIssues() {
            return hasSubIssues() || hasInvoiceIssues();
        }
    }
          public record RunResult(int accountsRun, int missingInvoices, int pastDue, int deviation, boolean issuesFound) {}
}
