package com.beworking.contacts;

/**
 * Published whenever a brand-new ContactProfile is persisted (admin "Crear
 * contacto", OV signup, booking-driven contact, …). Listeners react after the
 * transaction commits — currently used by the leads package to delete any
 * matching lead row (lead → customer conversion = lead row removed).
 *
 * <p>Carries only what listeners need (id + email) so we don't leak the full
 * entity across packages.
 */
public record ContactProfileCreatedEvent(Long contactProfileId, String email) {
}
