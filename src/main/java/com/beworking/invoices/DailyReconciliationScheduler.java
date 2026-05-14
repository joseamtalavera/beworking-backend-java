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
  
          if (hasIssues) {
              sendReport(results);
          } else {
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

        // 1b. Pendiente invoices for current year (mirrors dashboard pendingByAccount).
        //     Buckets by f.holdedcuenta with NULL → 'PT' default, matching the
        //     dashboard expression `(invoice.cuenta || 'PT').toUpperCase()`.
        //     Status keywords kept in sync with Overview.jsx pendingByAccount.
        Map<String, Object> pendiente = jdbcTemplate.queryForMap(
            "SELECT COUNT(*) AS cnt, COALESCE(SUM(f.total), 0) AS amt " +
            "  FROM beworking.facturas f " +
            " WHERE EXTRACT(YEAR FROM f.creacionfecha) = EXTRACT(YEAR FROM CURRENT_DATE) " +
            "   AND UPPER(COALESCE(NULLIF(f.holdedcuenta, ''), 'PT')) = ? " +
            "   AND (LOWER(COALESCE(f.estado,'')) LIKE '%pend%' " +
            "     OR LOWER(COALESCE(f.estado,'')) LIKE '%confir%' " +
            "     OR LOWER(COALESCE(f.estado,'')) LIKE '%fact%' " +
            "     OR LOWER(COALESCE(f.estado,'')) LIKE '%invoice%' " +
            "     OR LOWER(COALESCE(f.estado,'')) LIKE '%created%')",
            account);
        result.pendienteCount = ((Number) pendiente.get("cnt")).intValue();
        Object amt = pendiente.get("amt");
        result.pendienteAmount = amt != null ? new BigDecimal(amt.toString()) : BigDecimal.ZERO;

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

        logger.info("Reconciliation [{}]: dbActive={} stripeActive={} pastDue={} missing={} dbOnly={} stripeOnly={}",
            account, result.dbActive, result.stripeActive, result.stripePastDue,
            result.missingInvoices.size(), result.dbOnlySubIds.size(), result.stripeOnlySubIds.size());

        return result;
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
        String subject = "BeWorking — Subscription Reconciliation " + LocalDate.now();
        String fontStack = "-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Helvetica,Arial,sans-serif";

        StringBuilder html = new StringBuilder();
        html.append("<div style=\"font-family:").append(fontStack).append(";max-width:720px;margin:24px auto;color:#1a1a1a;background:#fafafa;padding:24px\">");

        // Outer card matching dashboard surface
        html.append("<div style='background:#fff;border:1px solid #e5e7eb;border-radius:12px;padding:28px 32px'>");

        // Header: title + last run date (mirrors dashboard ReconciliationCard header)
        html.append("<div style='display:flex;justify-content:space-between;align-items:baseline;border-bottom:1px solid #f0f0f0;padding-bottom:16px;margin-bottom:24px'>");
        html.append("<h1 style='margin:0;font-size:18px;font-weight:600;letter-spacing:-0.015em;color:#111'>Subscription Reconciliation</h1>");
        html.append("<span style='font-size:12px;color:#6b7280'>Last run: ").append(LocalDate.now()).append("</span>");
        html.append("</div>");

        for (AccountResult r : results) {
            boolean ok = !r.hasIssues();
            String accent = ok ? "#16a34a" : (r.missingInvoices.size() > 0 ? "#dc2626" : "#ea580c");
            String accentBg = ok ? "#f0fdf4" : (r.missingInvoices.size() > 0 ? "#fef2f2" : "#fff7ed");
            String statusLabel = r.error != null ? "Error" : (ok ? "OK" : (r.missingInvoices.size() > 0 ? "Alert" : "Warning"));
            String displayName = r.account.equals("GT") ? "GT" : "PT";
            String fullName = r.account.equals("GT") ? "GLOBALTECHNO OÜ" : "BeWorking Partners";

            html.append("<div style='border:1px solid #e5e7eb;border-radius:10px;overflow:hidden;margin-bottom:20px'>");

            // Sub-card header (matches dashboard card head with colored dot + total + status chip)
            html.append("<table style='width:100%;border-collapse:collapse;background:").append(accentBg).append(";border-bottom:1px solid #e5e7eb'>");
            html.append("<tr>");
            html.append("<td style='padding:12px 16px;vertical-align:middle'>");
            html.append("<span style='display:inline-block;width:8px;height:8px;border-radius:50%;background:").append(accent).append(";margin-right:8px;vertical-align:middle'></span>");
            html.append("<span style='font-size:14px;font-weight:700;color:#111;vertical-align:middle'>").append(displayName).append("</span>");
            html.append("<span style='font-size:13px;color:#6b7280;margin-left:4px;vertical-align:middle'> · ").append(fullName).append("</span>");
            html.append("<span style='font-size:12px;color:#6b7280;margin-left:8px;vertical-align:middle'>").append(r.total()).append(" total</span>");
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
            } else {
                // 6-metric grid (mirrors dashboard layout exactly)
                html.append("<table style='border-collapse:collapse;width:100%;table-layout:fixed'>");
                html.append("<tr>");
                addMetric(html, "Stripe", String.valueOf(r.stripeLive()), null, null);
                addMetric(html, "Scheduled", String.valueOf(r.dbScheduled), null, null);
                addMetric(html, "Transfer", String.valueOf(r.dbBankTransfer), null, null);
                addMetric(html, "Deviation", String.valueOf(r.deviation()), null,
                    r.deviation() > 0 ? "#dc2626" : null);
                addMetric(html, "Overdue", String.valueOf(r.stripePastDue),
                    r.stripePastDue > 0 ? formatEur(r.pastDueAmount) : null,
                    r.stripePastDue > 0 ? "#dc2626" : null);
                addMetric(html, "Pendiente", String.valueOf(r.pendienteCount),
                    r.pendienteCount > 0 ? formatEur(r.pendienteAmount) : null,
                    r.pendienteCount > 0 ? "#dc2626" : null);
                html.append("</tr></table>");

                if (!r.missingInvoices.isEmpty()) {
                    html.append("<p style='margin:18px 0 0;font-size:13px;color:#dc2626;font-weight:600'>")
                        .append(r.missingInvoices.size()).append(" missing invoice(s)")
                        .append("</p>");
                }
            }

            // Past due detail
            if (!r.pastDueSubs.isEmpty()) {
                html.append(sectionTitle("Past-due subscriptions", "#ea580c"));
                html.append("<table style='border-collapse:collapse;width:100%;font-size:13px;border:1px solid #f0f0f0;border-radius:6px'>");
                html.append("<tr style='background:#fafafa;border-bottom:1px solid #f0f0f0'>")
                    .append("<th style='text-align:left;padding:8px 12px;font-size:11px;font-weight:600;color:#6b7280;text-transform:uppercase;letter-spacing:0.05em'>Customer</th>")
                    .append("<th style='text-align:left;padding:8px 12px;font-size:11px;font-weight:600;color:#6b7280;text-transform:uppercase;letter-spacing:0.05em'>WhatsApp</th>")
                    .append("<th style='text-align:left;padding:8px 12px;font-size:11px;font-weight:600;color:#6b7280;text-transform:uppercase;letter-spacing:0.05em'>Stripe Sub</th>")
                    .append("<th style='text-align:right;padding:8px 12px;font-size:11px;font-weight:600;color:#6b7280;text-transform:uppercase;letter-spacing:0.05em'>Amount</th></tr>");
                for (Map<String, Object> sub : r.pastDueSubs) {
                    String name = (String) sub.get("customerName");
                    String email = (String) sub.get("customerEmail");
                    String phone = (String) sub.get("customerPhone");
                    String customer = name != null
                        ? "<span style='color:#111;font-weight:500'>" + name + "</span>"
                          + (email != null ? "<br><span style='color:#6b7280;font-size:12px'>" + email + "</span>" : "")
                        : "<span style='color:#9ca3af'>—</span>";
                    String waCell;
                    if (phone != null && !phone.isBlank()) {
                        String waNumber = phone.replaceAll("[^0-9]", "");
                        waCell = "<a href='https://wa.me/" + waNumber + "' "
                            + "style='display:inline-block;background:#25D366;color:#fff;text-decoration:none;"
                            + "padding:6px 12px;border-radius:14px;font-size:12px;font-weight:600;white-space:nowrap'>"
                            + "WhatsApp</a>";
                    } else {
                        waCell = "<span style='color:#9ca3af;font-size:12px'>—</span>";
                    }
                    String subId = String.valueOf(sub.get("subscriptionId"));
                    html.append("<tr style='border-bottom:1px solid #f5f5f5'>")
                        .append("<td style='padding:10px 12px;vertical-align:top'>").append(customer).append("</td>")
                        .append("<td style='padding:10px 12px;vertical-align:top'>").append(waCell).append("</td>")
                        .append("<td style='padding:10px 12px;vertical-align:top'>")
                        .append("<a href='https://dashboard.stripe.com/subscriptions/").append(subId)
                        .append("' style='color:#2563eb;font-family:ui-monospace,SFMono-Regular,Menlo,monospace;font-size:11px;text-decoration:none'>")
                        .append(subId).append("</a></td>")
                        .append("<td style='padding:10px 12px;vertical-align:top;text-align:right;white-space:nowrap;font-variant-numeric:tabular-nums;font-weight:600;color:#111'>")
                        .append(formatEur(sub.get("amountDue"))).append("</td></tr>");
                }
                html.append("</table>");
            }

            // Missing invoice detail
            if (!r.missingInvoices.isEmpty()) {
                html.append(sectionTitle("Missing invoices in DB", "#dc2626"));
                html.append("<p style='margin:0 0 8px;font-size:12px;color:#6b7280'>Paid in Stripe but no row in <code style='font-family:ui-monospace,monospace'>beworking.facturas</code>.</p>");
                html.append("<table style='border-collapse:collapse;width:100%;font-size:13px;border:1px solid #f0f0f0;border-radius:6px'>");
                html.append("<tr style='background:#fafafa;border-bottom:1px solid #f0f0f0'>")
                    .append("<th style='text-align:left;padding:8px 12px;font-size:11px;font-weight:600;color:#6b7280;text-transform:uppercase;letter-spacing:0.05em'>Stripe Invoice</th>")
                    .append("<th style='text-align:right;padding:8px 12px;font-size:11px;font-weight:600;color:#6b7280;text-transform:uppercase;letter-spacing:0.05em'>Action</th></tr>");
                for (Map<String, Object> inv : r.missingInvoices) {
                    String invId = (String) inv.get("stripeInvoiceId");
                    String url = (String) inv.get("stripeUrl");
                    html.append("<tr style='border-bottom:1px solid #f5f5f5'>")
                        .append("<td style='padding:10px 12px;font-family:ui-monospace,SFMono-Regular,Menlo,monospace;font-size:11px;color:#111'>")
                        .append(invId).append("</td>")
                        .append("<td style='padding:10px 12px;text-align:right'>")
                        .append("<a href='").append(url).append("' style='color:#2563eb;text-decoration:none;font-size:12px'>")
                        .append("Open in Stripe →</a></td></tr>");
                }
                html.append("</table>");
            }

            // DB-only sub IDs (cancelled in Stripe but DB still has them active)
            if (!r.dbOnlySubIds.isEmpty()) {
                html.append(sectionTitle("DB-only subs", "#ea580c"));
                html.append("<p style='margin:0 0 8px;font-size:12px;color:#6b7280'>Cancelled in Stripe, still active in DB.</p>");
                html.append("<div style='font-family:ui-monospace,SFMono-Regular,Menlo,monospace;font-size:11px;color:#374151;line-height:1.8;background:#fafafa;border:1px solid #f0f0f0;border-radius:6px;padding:10px 12px'>");
                for (String id : r.dbOnlySubIds) html.append(id).append("<br>");
                html.append("</div>");
            }

            // Stripe-only sub IDs (in Stripe but no DB record at all)
            if (!r.stripeOnlySubIds.isEmpty()) {
                html.append(sectionTitle("Stripe-only subs", "#ea580c"));
                html.append("<p style='margin:0 0 8px;font-size:12px;color:#6b7280'>Active in Stripe, no record in DB.</p>");
                html.append("<div style='font-family:ui-monospace,SFMono-Regular,Menlo,monospace;font-size:11px;color:#374151;line-height:1.8;background:#fafafa;border:1px solid #f0f0f0;border-radius:6px;padding:10px 12px'>");
                for (String id : r.stripeOnlySubIds) html.append(id).append("<br>");
                html.append("</div>");
            }

            html.append("</div>"); // body
            html.append("</div>"); // sub-card
        }

        html.append("</div>"); // outer card
        html.append("<p style='margin:16px 0 0;font-size:11px;color:#9ca3af;text-align:center'>BeWorking · Daily Reconciliation</p>");
        html.append("</div>");

        try {
            emailService.sendHtml(ADMIN_EMAIL, subject, html.toString());
            logger.info("Reconciliation report sent to {}", ADMIN_EMAIL);
        } catch (Exception e) {
            logger.error("Failed to send reconciliation report: {}", e.getMessage(), e);
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
        List<Map<String, Object>> pastDueSubs = new ArrayList<>();
        List<Map<String, Object>> missingInvoices = new ArrayList<>();
        List<String> dbOnlySubIds = new ArrayList<>();
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

        boolean hasIssues() {
            return error != null
                || !missingInvoices.isEmpty()
                || stripePastDue > 0
                || deviation() > 0
                || pendienteCount > 0
                || !stripeOnlySubIds.isEmpty();
        }
    }
          public record RunResult(int accountsRun, int missingInvoices, int pastDue, int deviation, boolean issuesFound) {}
}
