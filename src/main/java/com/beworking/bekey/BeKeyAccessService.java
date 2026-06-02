package com.beworking.bekey;

import com.beworking.contacts.ContactProfile;
import com.beworking.contacts.ContactProfileRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;

/**
 * Orchestrates BeKey (Akiles) access grants for BeWorking contacts.
 * Mirrors every state change into Akiles via {@link AkilesClient}, persists the
 * canonical record in bekey_access, and writes an audit row to bekey_events.
 */
@Service
public class BeKeyAccessService {

    private static final Logger LOGGER = LoggerFactory.getLogger(BeKeyAccessService.class);

    private final AkilesClient akiles;
    private final BeKeyAccessRepository accessRepository;
    private final BeKeyMemberGroupRepository memberGroupRepository;
    private final BeKeyMemberIdentityRepository beKeyMemberIdentityRepository;
    private final BeKeyDeviceRepository deviceRepository;
    private final ObjectMapper objectMapper;
    private final BeKeyEventRepository eventRepository;
    private final ContactProfileRepository contactProfileRepository;

    public BeKeyAccessService(
            AkilesClient akiles,
            BeKeyAccessRepository accessRepository,
            BeKeyMemberGroupRepository memberGroupRepository,
            BeKeyMemberIdentityRepository memberIdentityRepository,
            BeKeyDeviceRepository deviceRepository,
            BeKeyEventRepository eventRepository,
            ContactProfileRepository contactProfileRepository,
            ObjectMapper objectMapper
    ) {
        this.akiles = akiles;
        this.accessRepository = accessRepository;
        this.memberGroupRepository = memberGroupRepository;
        this.beKeyMemberIdentityRepository = memberIdentityRepository;
        this.deviceRepository = deviceRepository;
        this.eventRepository = eventRepository;
        this.contactProfileRepository = contactProfileRepository;
        this.objectMapper = objectMapper;
    }

    /** All currently-active (non-revoked) grants for a contact. */
    @Transactional(readOnly = true)
    public List<BeKeyAccess> listForContact(Long contactId) {
        return accessRepository.findByContactIdAndRevokedAtIsNull(contactId);
    }

    /** All currently-active (non-revoked) grants on a member group. */
    @Transactional(readOnly = true)
    public List<BeKeyAccess> listForMemberGroup(Long memberGroupId) {
        return accessRepository.findByMemberGroupIdAndRevokedAtIsNull(memberGroupId);
    }

    /**
     * Resolves the doors a contact may currently open: active grants -> member
     * groups -> Akiles permission rules -> matching bekey_devices. Empty if the
     * contact has no active grants.
     */
    @Transactional(readOnly = true)
    public List<BeKeyDevice> listAccessibleDevices(Long contactId) {
        Map<Long, BeKeyDevice> byId = new LinkedHashMap<>();   // dedup, preserve order
        for (BeKeyAccess grant : accessRepository.findByContactIdAndRevokedAtIsNull(contactId)) {
            BeKeyMemberGroup group = memberGroupRepository.findById(grant.getMemberGroupId()).orElse(null);
            if (group == null) continue;
            Map<String, Object> akilesGroup = akiles.getMemberGroup(group.getAkilesGroupId());
            Object permsObj = akilesGroup.get("permissions");
            if (!(permsObj instanceof List<?> perms)) continue;
            for (Object ruleObj : perms) {
                if (!(ruleObj instanceof Map<?, ?> rule)) continue;
                String gadgetId = (String) rule.get("gadget_id");
                String siteId = (String) rule.get("site_id");
                if (gadgetId != null) {
                    deviceRepository.findByAkilesGadgetId(gadgetId).ifPresent(d -> byId.put(d.getId(), d));
                } else if (siteId != null) {
                    deviceRepository.findByAkilesSiteId(siteId).forEach(d -> byId.put(d.getId(), d));
                } else {
                    deviceRepository.findAll().forEach(d -> byId.put(d.getId(), d));  // empty rule {} = all gadgets
                }
            }
        }
        return List.copyOf(byId.values());
    }

    /**
     * The contact's cleartext keypad PIN (the fallback when the app isn't handy),
     * or null if they have no Akiles identity / no PIN. Caches the pin id on the
     * identity so subsequent reads skip the list lookup.
     */
    @Transactional
    public String getRevealedPin(Long contactId) {
        BeKeyMemberIdentity identity = beKeyMemberIdentityRepository.findById(contactId).orElse(null);
        if (identity == null) return null;
        String memberId = identity.getAkilesMemberId();

        String pinId = identity.getAkilesPinId();
        if (pinId == null) {
            Map<String, Object> pins = akiles.listMemberPins(memberId);
            if (!(pins.get("data") instanceof List<?> data) || data.isEmpty()) return null;
            if (!(data.get(0) instanceof Map<?, ?> pin)) return null;
            pinId = (String) pin.get("id");
            if (pinId == null) return null;
            identity.setAkilesPinId(pinId);
            beKeyMemberIdentityRepository.save(identity);
        }

        Map<String, Object> revealed = akiles.revealMemberPin(memberId, pinId);
        return (String) revealed.get("pin");
    }

    /**
     * Grants a contact access to a member group and mirrors it into Akiles.
     * Idempotent on (source, sourceRef): if an active grant already exists for
     * that pair it is returned unchanged. Manual grants (null sourceRef) always
     * create a new row.
     *
     * @param contactId     beworking.contact_profiles id
     * @param memberGroupId beworking.bekey_member_groups id (NOT the akiles group id)
     * @param source        what triggered the grant (subscription / booking / manual)
     * @param sourceRef     id of the triggering subscription/booking, or null for manual
     * @param expiresAt     when access ends, or null for unbounded
     */
    @Transactional
    public BeKeyAccess grant(Long contactId, Long memberGroupId, BeKeyAccess.Source source,
                             Long sourceRef, OffsetDateTime expiresAt) {
        // 1. Idempotency - if this source already holds an active grant, return it.
        if (sourceRef != null) {
            Optional<BeKeyAccess> existing = accessRepository.findBySourceAndSourceRef(source, sourceRef);
            if (existing.isPresent() && existing.get().getRevokedAt() == null) {
                LOGGER.info("grant: active access {} already exists for {}:{}, returning it",
                        existing.get().getId(), source, sourceRef);
                return existing.get();
            }
        }

        // 2. Resolve the member group -> Akiles group id.
        BeKeyMemberGroup group = memberGroupRepository.findById(memberGroupId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown member group id: " + memberGroupId));

        // 3. Resolve-or-create the contact's stable Akiles member identity.
        BeKeyMemberIdentity identity = beKeyMemberIdentityRepository.findById(contactId)
                .orElseGet(() -> createIdentity(contactId));

        // 4. Mirror the grant into Akiles as a group association.
        OffsetDateTime startsAt = OffsetDateTime.now();
        Map<String, Object> assoc = akiles.addGroupAssociation(
                identity.getAkilesMemberId(),
                group.getAkilesGroupId(),
                startsAt.toInstant(),
                expiresAt != null ? expiresAt.toInstant() : null);
        String associationId = (String) assoc.get("id");
        if (associationId == null) {
            throw new IllegalStateException("Akiles addGroupAssociation returned no id for contact " + contactId);
        }

        // 5. Persist the canonical record.
        BeKeyAccess access = new BeKeyAccess();
        access.setContactId(contactId);
        access.setAkilesMemberId(identity.getAkilesMemberId());
        access.setAkilesAssociationId(associationId);
        access.setMemberGroupId(memberGroupId);
        access.setSource(source);
        access.setSourceRef(sourceRef);
        access.setStartsAt(startsAt);
        access.setEndsAt(expiresAt);
        access = accessRepository.save(access);

        LOGGER.info("grant: created access {} for contact {} on group {} (source {}:{})",
                access.getId(), contactId, group.getAkilesGroupId(), source, sourceRef);

        // Audit: record the grant in bekey_events.
        writeAuditEvent("access.granted", "svc:grant:" + access.getId(), access, null);
        return access;
    }

    /**
     * Revokes an access grant and removes the association in Akiles.
     * Idempotent: revoking an already-revoked grant is a no-op.
     *
     * @param accessId bekey_access id to revoke
     * @param reason   free-text reason (admin action, sub cancelled, expired…)
     */
    @Transactional
    public BeKeyAccess revoke(Long accessId, String reason) {
        BeKeyAccess access = accessRepository.findById(accessId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown access id: " + accessId));

        // Idempotency - already revoked, nothing to do.
        if (access.getRevokedAt() != null) {
            LOGGER.info("revoke: access {} already revoked, no-op", accessId);
            return access;
        }

        // Remove the association in Akiles first, then mark our row revoked.
        akiles.removeGroupAssociation(access.getAkilesMemberId(), access.getAkilesAssociationId());

        access.setRevokedAt(OffsetDateTime.now());
        access = accessRepository.save(access);

        LOGGER.info("revoke: access {} revoked (reason: {})", accessId, reason);

        // Audit: record the revoke in bekey_events.
        writeAuditEvent("access.revoked", "svc:revoke:" + access.getId(), access, reason);
        return access;
    }

    /**
     * Opens a door for a contact, enforcing that the contact actually has access.
     * Order matters: authorize, then fire the (irreversible) Akiles action, then audit.
     *
     * @throws SecurityException if no active grant covers the device
     */
    @Transactional
    public BeKeyDevice openDoor(Long contactId, Long deviceId) {
        BeKeyDevice device = listAccessibleDevices(contactId).stream()
                .filter(d -> d.getId().equals(deviceId))
                .findFirst()
                .orElseThrow(() -> new SecurityException(
                        "Contact " + contactId + " has no access to device " + deviceId));

        akiles.doGadgetAction(device.getAkilesGadgetId(), device.getActionId());

        LOGGER.info("openDoor: contact {} opened device {} (gadget {})",
                contactId, deviceId, device.getAkilesGadgetId());
        writeDoorEvent(contactId, device);
        return device;
    }

    /** Writes a door-open audit row to bekey_events with a synthetic event id. */
    private void writeDoorEvent(Long contactId, BeKeyDevice device) {
        try {
            OffsetDateTime now = OffsetDateTime.now();
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("contactId", contactId);
            payload.put("deviceId", device.getId());
            payload.put("akilesGadgetId", device.getAkilesGadgetId());
            payload.put("actionId", device.getActionId());

            BeKeyEvent event = new BeKeyEvent();
            event.setAkilesEventId("svc:open:" + device.getId() + ":" + now.toInstant().toEpochMilli());
            event.setContactId(contactId);
            event.setDeviceId(device.getId());
            event.setEventType("door.opened");
            event.setOccurredAt(now);
            event.setRaw(objectMapper.writeValueAsString(payload));
            eventRepository.save(event);
        } catch (JsonProcessingException e) {
            LOGGER.warn("failed to write door-open audit event for device {}: {}", device.getId(), e.getMessage());
        }
    }

    /** Creates a fresh Akiles member for a contact and stores the identity mapping. */
    private BeKeyMemberIdentity createIdentity(Long contactId) {
        ContactProfile contact = contactProfileRepository.findById(contactId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown contact id: " + contactId));
        Map<String, Object> created = akiles.createMember(contact.getName(), null, null);
        String memberId = (String) created.get("id");
        if (memberId == null) {
            throw new IllegalStateException("Akiles createMember returned no id for contact " + contactId);
        }
        BeKeyMemberIdentity identity = new BeKeyMemberIdentity();
        identity.setContactId(contactId);
        identity.setAkilesMemberId(memberId);
        return beKeyMemberIdentityRepository.save(identity);
    }

    /** Writes a service-side audit row to bekey_events with a synthetic event id. */
    private void writeAuditEvent(String eventType, String syntheticId, BeKeyAccess access, String reason) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("accessId", access.getId());
            payload.put("contactId", access.getContactId());
            payload.put("memberGroupId", access.getMemberGroupId());
            payload.put("source", access.getSource().name());
            payload.put("sourceRef", access.getSourceRef());
            payload.put("akilesMemberId", access.getAkilesMemberId());
            payload.put("akilesAssociationId", access.getAkilesAssociationId());
            if (reason != null) payload.put("reason", reason);

            BeKeyEvent event = new BeKeyEvent();
            event.setAkilesEventId(syntheticId);
            event.setContactId(access.getContactId());
            event.setEventType(eventType);
            event.setOccurredAt(OffsetDateTime.now());
            event.setRaw(objectMapper.writeValueAsString(payload));
            eventRepository.save(event);
        } catch (JsonProcessingException e) {
            // Audit failure must never break the grant/revoke transaction.
            LOGGER.warn("failed to write bekey audit event {}: {}", syntheticId, e.getMessage());
        }
    }

}
