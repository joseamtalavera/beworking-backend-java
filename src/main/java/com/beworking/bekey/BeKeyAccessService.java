package com.beworking.bekey;

import com.beworking.contacts.ContactProfile;
import com.beworking.contacts.ContactProfileRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
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

    /** Master kill-switch for all Akiles writes (#244). Default false: safe everywhere until set true in the prod task-def. */
    private final boolean integrationEnabled;

    public BeKeyAccessService(
            AkilesClient akiles,
            BeKeyAccessRepository accessRepository,
            BeKeyMemberGroupRepository memberGroupRepository,
            BeKeyMemberIdentityRepository memberIdentityRepository,
            BeKeyDeviceRepository deviceRepository,
            BeKeyEventRepository eventRepository,
            ContactProfileRepository contactProfileRepository,
            ObjectMapper objectMapper,
            @Value("${akiles.integration.enabled:false}") boolean integrationEnabled
    ) {
        this.akiles = akiles;
        this.accessRepository = accessRepository;
        this.memberGroupRepository = memberGroupRepository;
        this.beKeyMemberIdentityRepository = memberIdentityRepository;
        this.deviceRepository = deviceRepository;
        this.eventRepository = eventRepository;
        this.contactProfileRepository = contactProfileRepository;
        this.objectMapper = objectMapper;
        this.integrationEnabled = integrationEnabled;
        LOGGER.info("BeKeyAccessService initialized: akiles.integration.enabled={}", integrationEnabled);
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
        return grant(contactId, memberGroupId, source, sourceRef, OffsetDateTime.now(), expiresAt);
    }

    /**
     * Full grant with an explicit access-window start. Booking grants pin startsAt
     * to the booking day, so a future booking paid today only opens the door on the
     * booking day — Akiles enforces the window. A null startsAt defaults to now().
     */
    @Transactional
    public BeKeyAccess grant(Long contactId, Long memberGroupId, BeKeyAccess.Source source,
                             Long sourceRef, OffsetDateTime startsAt, OffsetDateTime expiresAt) {
        // 0. Master kill-switch (#244) - never write to Akiles when the integration is disabled.
        if (!integrationEnabled) {
            LOGGER.warn("grant skipped: akiles.integration.enabled=false (contact {}, source {}:{})",
                    contactId, source, sourceRef);
            return null;
        }

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
        OffsetDateTime effectiveStart = (startsAt != null) ? startsAt : OffsetDateTime.now();
        Map<String, Object> assoc = akiles.addGroupAssociation(
                identity.getAkilesMemberId(),
                group.getAkilesGroupId(),
                effectiveStart.toInstant(),
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
        access.setStartsAt(effectiveStart);
        access.setEndsAt(expiresAt);
        access = accessRepository.save(access);

        LOGGER.info("grant: created access {} for contact {} on group {} (source {}:{})",
                access.getId(), contactId, group.getAkilesGroupId(), source, sourceRef);

        // Audit: record the grant in bekey_events.
        writeAuditEvent("access.granted", "svc:grant:" + access.getId(), access, null);
        return access;
    }

    /**
     * Auto-grants door access for a subscription based on its category (#148).
     * Mapping: "coworking" -> MA1O1 (desks + street door). Other categories
     * (virtual_office, meeting_room, extra) get NO standing access — virtual-office
     * access is booking-driven, not subscription-driven. Idempotent via grant()'s
     * (source, sourceRef) dedup. Returns the grant, or null if no access applies.
     */
    @Transactional
    public BeKeyAccess grantForSubscription(Long contactId, Long subscriptionId, String category) {
        String label = (category != null && category.equalsIgnoreCase("coworking")) ? "MA1O1" : null;
        if (label == null) {
            LOGGER.info("grantForSubscription: category '{}' -> no standing access (sub {})", category, subscriptionId);
            return null;
        }
        BeKeyMemberGroup group = memberGroupRepository.findByLabel(label)
                .orElseThrow(() -> new IllegalStateException("BeKey member group '" + label + "' not found"));
        return grant(contactId, group.getId(), BeKeyAccess.Source.subscription, subscriptionId, null);
    }

    /**
     * Auto-revokes all active door grants tied to a subscription (#149).
     * Mirror of grantForSubscription: when a sub is cancelled/deactivated, every
     * active grant with source=subscription and sourceRef=subscriptionId is revoked
     * (Akiles association removed + row marked revoked + audited). Best-effort per
     * grant — one failure doesn't abort the rest.
     */
    @Transactional
    public void revokeForSubscription(Long subscriptionId, String reason) {
        List<BeKeyAccess> grants = accessRepository.findBySourceAndSourceRefAndRevokedAtIsNull(
                BeKeyAccess.Source.subscription, subscriptionId);
        for (BeKeyAccess g : grants) {
            try {
                revoke(g.getId(), reason);
            } catch (Exception ex) {
                LOGGER.warn("revokeForSubscription: failed to revoke access {} for sub {}: {}",
                        g.getId(), subscriptionId, ex.getMessage());
            }
        }
    }

    /**
     * Auto-grants time-boxed door access for a single booked slot (bloqueo) (#150).
     * Maps the booked product code (producto.nombre) to its BeKey member group:
     * "MA1A1" -> MA1O1 (desks share the office group); "MA1A2".."MA1A5" -> same-named
     * group. Virtual-office products (MA1O1-N) and any unmapped code get NO door.
     * The window runs startsAt..expiresAt (that slot's day), so the key opens only on
     * the booked day. Idempotent via grant()'s (source, sourceRef=bloqueoId) dedup.
     * Returns the grant, or null if the room has no door.
     */
    @Transactional
    public BeKeyAccess grantForBloqueo(Long contactId, Long bloqueoId, String roomCode,
                                       OffsetDateTime startsAt, OffsetDateTime expiresAt) {
        String label = resolveBookingGroupLabel(roomCode);
        if (label == null) {
            LOGGER.info("grantForBloqueo: room '{}' -> no door access (bloqueo {})", roomCode, bloqueoId);
            return null;
        }
        BeKeyMemberGroup group = memberGroupRepository.findByLabel(label)
                .orElseThrow(() -> new IllegalStateException("BeKey member group '" + label + "' not found"));
        return grant(contactId, group.getId(), BeKeyAccess.Source.booking, bloqueoId, startsAt, expiresAt);
    }

    /** Maps a booked product code to a BeKey member-group label, or null if it has no door. */
    private String resolveBookingGroupLabel(String roomCode) {
        if (roomCode == null) {
            return null;
        }
        String code = roomCode.trim().toUpperCase();
        if (code.equals("MA1A1")) {
            return "MA1O1";              // desks share the office group
        }
        if (code.matches("MA1A[2-5]")) {
            return code;                 // aulas map to their same-named group
        }
        return null;                     // MA1O1-N (virtual office) + anything unmapped
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
        // Master kill-switch (#244) - never call Akiles when the integration is disabled.
        if (!integrationEnabled) {
            LOGGER.warn("revoke skipped: akiles.integration.enabled=false (access {})", accessId);
            return null;
        }

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
     * Edits the access window of a MANUAL grant (admin override). Booking/subscription
     * grants are projections of their source and must not be edited here — the reconcile
     * would overwrite them. Akiles has no association-update, so this removes the old
     * association and adds a new one with the new window, in place on the same row.
     *
     * @throws IllegalStateException if the grant is not manual, already revoked, or the integration is off
     */
    @Transactional
    public BeKeyAccess updateManualWindow(Long accessId, OffsetDateTime startsAt, OffsetDateTime expiresAt) {
        if (!integrationEnabled) {
            LOGGER.warn("updateManualWindow skipped: akiles.integration.enabled=false (access {})", accessId);
            return null;
        }
        BeKeyAccess access = accessRepository.findById(accessId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown access id: " + accessId));
        if (access.getSource() != BeKeyAccess.Source.manual) {
            throw new IllegalStateException("Only manual grants can be edited; this grant is managed by " + access.getSource());
        }
        if (access.getRevokedAt() != null) {
            throw new IllegalStateException("Cannot edit a revoked grant");
        }
        BeKeyMemberGroup group = memberGroupRepository.findById(access.getMemberGroupId())
                .orElseThrow(() -> new IllegalStateException("Member group missing for access " + accessId));

        OffsetDateTime effectiveStart = (startsAt != null) ? startsAt : access.getStartsAt();

        // Akiles has no association-update: drop the old window, add the new one.
        akiles.removeGroupAssociation(access.getAkilesMemberId(), access.getAkilesAssociationId());
        Map<String, Object> assoc = akiles.addGroupAssociation(
                access.getAkilesMemberId(), group.getAkilesGroupId(),
                effectiveStart.toInstant(), expiresAt != null ? expiresAt.toInstant() : null);
        String associationId = (String) assoc.get("id");
        if (associationId == null) {
            throw new IllegalStateException("Akiles addGroupAssociation returned no id for access " + accessId);
        }

        access.setAkilesAssociationId(associationId);
        access.setStartsAt(effectiveStart);
        access.setEndsAt(expiresAt);
        access = accessRepository.save(access);

        LOGGER.info("updateManualWindow: access {} re-windowed (start {}, end {})", accessId, effectiveStart, expiresAt);
        writeAuditEvent("access.updated", "svc:update:" + access.getId() + ":" + effectiveStart.toInstant().toEpochMilli(), access, null);
        return access;
    }

    /** Every door in the building — the admin "master key" view, no grant required. */
    @Transactional(readOnly = true)
    public List<BeKeyDevice> listAllDevices() {
        return deviceRepository.findAll();
    }

    /**
     * Opens a door for a contact, enforcing that the contact actually has access.
     *
     * @throws SecurityException if no active grant covers the device
     */
    @Transactional
    public BeKeyDevice openDoor(Long contactId, Long deviceId) {
        return openDoor(contactId, deviceId, false);
    }

    /**
     * Opens a door. With {@code adminOverride}, any door may be opened with no grant
     * (the admin "master key": e.g. buzzing in a delivery at the street door). The
     * gadget is actuated with the org API token, so no Akiles member is needed for
     * the admin. Order matters: authorize, then fire the (irreversible) Akiles action,
     * then audit.
     *
     * @throws SecurityException if (non-admin) no active grant covers the device, or the device is unknown
     */
    @Transactional
    public BeKeyDevice openDoor(Long contactId, Long deviceId, boolean adminOverride) {
        // Master kill-switch (#244) - never actuate a real door when the integration is disabled.
        if (!integrationEnabled) {
            throw new IllegalStateException("BeKey integration disabled (akiles.integration.enabled=false)");
        }

        BeKeyDevice device = adminOverride
                ? deviceRepository.findById(deviceId)
                        .orElseThrow(() -> new SecurityException("Unknown device " + deviceId))
                : listAccessibleDevices(contactId).stream()
                        .filter(d -> d.getId().equals(deviceId))
                        .findFirst()
                        .orElseThrow(() -> new SecurityException(
                                "Contact " + contactId + " has no access to device " + deviceId));

        akiles.doGadgetAction(device.getAkilesGadgetId(), device.getActionId());

        LOGGER.info("openDoor{}: contact {} opened device {} (gadget {})",
                adminOverride ? "(admin)" : "", contactId, deviceId, device.getAkilesGadgetId());
        // The door already opened — never let the audit write fail the request.
        // bekey_events.contact_id has an FK to contact_profiles, so an admin whose
        // account isn't a real contact (e.g. contact 1) would FK-violate and roll
        // back a successful open. Only audit when the contact actually exists.
        if (contactId != null && contactProfileRepository.existsById(contactId)) {
            writeDoorEvent(contactId, device);
        }
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
