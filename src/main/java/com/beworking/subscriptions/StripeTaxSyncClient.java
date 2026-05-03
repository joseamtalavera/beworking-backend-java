package com.beworking.subscriptions;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Pushes the backend's authoritative VAT decision into Stripe so the Stripe-side
 * tax config matches the locked vat_percent. Without this, Stripe's
 * default_tax_rates (set at sub creation) drifts from BeWorking's lock-in and
 * customers get charged a different rate than their canonical invoice shows.
 *
 * Best-effort: failures are logged but never thrown — the local DB lock is the
 * source of truth, and a stale Stripe config can be reconciled later via the
 * admin bulk-sync endpoint.
 */
@Component
public class StripeTaxSyncClient {

    private static final Logger logger = LoggerFactory.getLogger(StripeTaxSyncClient.class);

    private final RestClient http;
    private final String paymentsBaseUrl;

    public StripeTaxSyncClient(
            @Value("${app.payments.base-url:http://beworking-stripe-service:8081}") String paymentsBaseUrl) {
        this.paymentsBaseUrl = paymentsBaseUrl;
        this.http = RestClient.create();
    }

    /** Returns true if Stripe accepted the update; false on any error. Never throws. */
    public boolean syncSubscriptionTax(String stripeSubscriptionId, int vatPercent, String cuenta) {
        if (stripeSubscriptionId == null || stripeSubscriptionId.isBlank()) return false;
        if (paymentsBaseUrl == null || paymentsBaseUrl.isBlank()) {
            logger.warn("Stripe payments base URL not configured — skipping tax sync for sub {}",
                stripeSubscriptionId);
            return false;
        }

        boolean taxExempt = vatPercent == 0;
        String tenant = "GT".equalsIgnoreCase(cuenta) ? "gt" : "bw";

        try {
            http.post()
                .uri(paymentsBaseUrl + "/api/subscriptions/" + stripeSubscriptionId + "/sync-tax")
                .header("Content-Type", "application/json")
                .body(Map.of(
                    "vat_percent", vatPercent,
                    "tax_exempt", taxExempt,
                    "tenant", tenant
                ))
                .retrieve()
                .toBodilessEntity();
            logger.info("Synced Stripe tax for sub {}: vat_percent={}, tax_exempt={}, tenant={}",
                stripeSubscriptionId, vatPercent, taxExempt, tenant);
            return true;
        } catch (Exception e) {
            logger.warn("Failed to sync Stripe tax for sub {} (vat_percent={}, tenant={}): {}",
                stripeSubscriptionId, vatPercent, tenant, e.getMessage());
            // Intentionally not rethrown — local lock is the source of truth.
            return false;
        }
    }
}
