package com.beworking.subscriptions;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.beworking.contacts.ContactBillingChangedEvent;

/**
 * Pushes a contact's billing identity to Stripe whenever it changes in our DB.
 *
 * Listens for {@link ContactBillingChangedEvent} (raised by the three entry
 * points: profile edit, single VAT revalidation, bulk VAT reseed) and mirrors
 * name / tax id / tax-exempt onto the Stripe customer of every active
 * subscription the contact has. Forward-only: the Stripe customer is a live
 * object; past Stripe invoices are immutable and untouched — same principle as
 * the facturas billing snapshot.
 *
 * AFTER_COMMIT so the Stripe push reflects the committed DB state and never
 * runs for a rolled-back change. Best-effort: the client never throws.
 */
@Component
public class ContactBillingStripeSyncListener {

    private static final Logger logger = LoggerFactory.getLogger(ContactBillingStripeSyncListener.class);

    private final JdbcTemplate jdbcTemplate;
    private final SubscriptionService subscriptionService;
    private final StripeTaxSyncClient stripeTaxSyncClient;

    public ContactBillingStripeSyncListener(JdbcTemplate jdbcTemplate,
                                            SubscriptionService subscriptionService,
                                            StripeTaxSyncClient stripeTaxSyncClient) {
        this.jdbcTemplate = jdbcTemplate;
        this.subscriptionService = subscriptionService;
        this.stripeTaxSyncClient = stripeTaxSyncClient;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onContactBillingChanged(ContactBillingChangedEvent event) {
        Long contactId = event.contactId();
        if (contactId == null) return;

        Map<String, Object> profile;
        try {
            profile = jdbcTemplate.queryForMap(
                "SELECT name, billing_name, billing_tax_id, billing_tax_id_type, vat_valid"
                    + " FROM beworking.contact_profiles WHERE id = ?",
                contactId);
        } catch (Exception e) {
            logger.warn("Stripe identity sync skipped — no profile for contact {}: {}",
                contactId, e.getMessage());
            return;
        }

        String billingName = str(profile.get("billing_name"));
        String name = str(profile.get("name"));
        String displayName = (billingName != null && !billingName.isBlank()) ? billingName : name;
        String taxId = str(profile.get("billing_tax_id"));
        String taxIdType = str(profile.get("billing_tax_id_type"));
        boolean vatValid = Boolean.TRUE.equals(profile.get("vat_valid"));

        List<Subscription> subs = subscriptionService.findByContactIdAndActiveTrue(contactId);
        if (subs.isEmpty()) {
            logger.debug("Stripe identity sync: contact {} has no active subscription — nothing to push", contactId);
            return;
        }

        // A contact can have several subs sharing one Stripe customer — push once each.
        Set<String> pushed = new HashSet<>();
        for (Subscription sub : subs) {
            String customerId = sub.getStripeCustomerId();
            if (customerId == null || customerId.isBlank() || !pushed.add(customerId)) continue;

            // tax_exempt follows the locked sub VAT (0% ⇒ exempt); fall back to
            // the contact's VIES-valid flag when the sub has no rate yet.
            boolean taxExempt = sub.getVatPercent() != null
                ? sub.getVatPercent() == 0
                : vatValid;

            stripeTaxSyncClient.syncCustomerIdentity(
                customerId, displayName, taxId, taxIdType, taxExempt, sub.getCuenta());
        }
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }
}
