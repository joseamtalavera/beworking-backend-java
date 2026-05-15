package com.beworking.contacts;

/**
 * Raised when a contact's billing identity or VAT data changes in our DB
 * (profile edit, single VAT revalidation, bulk VAT reseed). Consumed in the
 * subscriptions package to push name / tax id / tax-exempt onto the Stripe
 * customer — keeping Stripe in lockstep with our DB for all future invoices.
 *
 * Decoupled via an application event so the three entry points don't take a
 * dependency on the Stripe layer (and no DI cycle with SubscriptionService).
 *
 * @param contactId beworking.contact_profiles.id
 */
public record ContactBillingChangedEvent(Long contactId) {}
