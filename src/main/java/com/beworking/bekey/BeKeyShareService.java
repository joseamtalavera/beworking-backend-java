package com.beworking.bekey;

import com.beworking.auth.EmailService;
import com.beworking.auth.RegisterService;
import com.beworking.auth.User;
import com.beworking.auth.UserRepository;
import com.beworking.contacts.ContactProfile;
import com.beworking.contacts.ContactProfileRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Share-my-access (#243): a member lends their BeKey access to a guest for a
 * bounded window. A share spawns a normal {@code source=shared} grant for the
 * guest's contact, so the existing door-open authz enforces it — no new open
 * path, no magic link. The guest opens the door from inside the app.
 *
 * Rules: window required and capped at {@link #MAX_HOURS}h; at most
 * {@link #MAX_ACTIVE_SHARES} active shares per member; only a member who holds
 * real (non-shared) access may share, and they share the group they hold.
 */
@Service
public class BeKeyShareService {

    private static final Logger LOGGER = LoggerFactory.getLogger(BeKeyShareService.class);
    private static final int MAX_ACTIVE_SHARES = 3;
    private static final long MAX_HOURS = 24;

    private final BeKeyShareRepository shareRepository;
    private final BeKeyAccessService accessService;
    private final BeKeyAccessRepository accessRepository;
    private final ContactProfileRepository contactProfileRepository;
    private final RegisterService registerService;
    private final UserRepository userRepository;
    private final EmailService emailService;
    private final boolean integrationEnabled;

    public BeKeyShareService(BeKeyShareRepository shareRepository,
                             BeKeyAccessService accessService,
                             BeKeyAccessRepository accessRepository,
                             ContactProfileRepository contactProfileRepository,
                             RegisterService registerService,
                             UserRepository userRepository,
                             EmailService emailService,
                             @Value("${akiles.integration.enabled:false}") boolean integrationEnabled) {
        this.shareRepository = shareRepository;
        this.accessService = accessService;
        this.accessRepository = accessRepository;
        this.contactProfileRepository = contactProfileRepository;
        this.registerService = registerService;
        this.userRepository = userRepository;
        this.emailService = emailService;
        this.integrationEnabled = integrationEnabled;
    }

    /** Active (non-revoked) shares a member created, newest first. */
    @Transactional(readOnly = true)
    public List<BeKeyShare> listForSharer(Long sharerContactId) {
        return shareRepository.findBySharerContactIdAndRevokedAtIsNullOrderByCreatedAtDesc(sharerContactId);
    }

    @Transactional
    public BeKeyShare createShare(Long sharerContactId, String guestName, String guestEmail,
                                  OffsetDateTime startsAt, OffsetDateTime endsAt) {
        if (!integrationEnabled) {
            throw new IllegalStateException("BeKey integration disabled");
        }
        if (guestEmail == null || guestEmail.isBlank()) {
            throw new IllegalArgumentException("Guest email is required");
        }
        final String email = guestEmail.trim().toLowerCase();

        // 1. Validate the window (required, future, <= MAX_HOURS).
        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime start = (startsAt != null) ? startsAt : now;
        if (endsAt == null) {
            throw new IllegalArgumentException("End time is required");
        }
        if (!endsAt.isAfter(start)) {
            throw new IllegalArgumentException("End time must be after the start");
        }
        if (endsAt.isBefore(now)) {
            throw new IllegalArgumentException("End time is in the past");
        }
        if (Duration.between(start, endsAt).toHours() > MAX_HOURS) {
            throw new IllegalArgumentException("A share can last at most " + MAX_HOURS + " hours");
        }

        // 2. The sharer must hold real (non-shared) access — share the group they hold.
        BeKeyAccess base = accessRepository.findByContactIdAndRevokedAtIsNull(sharerContactId).stream()
                .filter(g -> g.getSource() != BeKeyAccess.Source.shared)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("You have no access to share"));
        Long memberGroupId = base.getMemberGroupId();

        // 3. Per-member cap on active shares.
        if (shareRepository.countBySharerContactIdAndRevokedAtIsNull(sharerContactId) >= MAX_ACTIVE_SHARES) {
            throw new IllegalStateException("You already have " + MAX_ACTIVE_SHARES + " active shares — revoke one first");
        }

        // 4. Resolve sharer (for the email + self-share guard) and the guest contact.
        ContactProfile sharer = contactProfileRepository.findById(sharerContactId).orElse(null);
        if (sharer != null && email.equalsIgnoreCase(sharer.getEmailPrimary())) {
            throw new IllegalArgumentException("You can't share access with yourself");
        }
        Long guestContactId = resolveOrCreateGuestContact(email, guestName);

        // 5. Free guest account; only NEW accounts get a password-setup link.
        boolean newUser = userRepository.findByEmail(email).isEmpty();
        User guestUser = registerService.createUserForContactSilent(email, guestName, guestContactId);
        String setupToken = (newUser && guestUser != null)
                ? registerService.generatePasswordSetupToken(guestUser.getId(), Duration.ofDays(7))
                : null;

        // 6. Persist the share, spawn the grant, link them.
        BeKeyShare share = new BeKeyShare();
        share.setSharerContactId(sharerContactId);
        share.setGuestContactId(guestContactId);
        share.setGuestEmail(email);
        share.setGuestName(guestName);
        share.setMemberGroupId(memberGroupId);
        share.setStartsAt(start);
        share.setEndsAt(endsAt);
        share = shareRepository.save(share);

        BeKeyAccess access = accessService.grant(
                guestContactId, memberGroupId, BeKeyAccess.Source.shared, share.getId(), start, endsAt);
        if (access == null) {
            throw new IllegalStateException("Could not create the access grant");
        }
        share.setAccessId(access.getId());
        share = shareRepository.save(share);

        // 7. Invite email (best-effort — the grant already exists).
        try {
            String sharerName = (sharer != null && sharer.getName() != null) ? sharer.getName() : "Un miembro de BeWorking";
            emailService.sendBeKeyShareInvite(email, guestName, sharerName, start, endsAt, setupToken);
        } catch (Exception e) {
            LOGGER.warn("BeKey share invite email failed for {}: {}", email, e.getMessage());
        }

        LOGGER.info("BeKey share {} created: sharer {} -> guest {} on group {} ({} .. {})",
                share.getId(), sharerContactId, guestContactId, memberGroupId, start, endsAt);
        return share;
    }

    /** Revokes a share the requester owns (and its underlying grant). */
    @Transactional
    public void revoke(Long shareId, Long requesterContactId) {
        BeKeyShare share = shareRepository.findById(shareId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown share id: " + shareId));
        if (!share.getSharerContactId().equals(requesterContactId)) {
            throw new SecurityException("Not your share");
        }
        if (share.getRevokedAt() != null) {
            return;
        }
        if (share.getAccessId() != null) {
            try {
                accessService.revoke(share.getAccessId(), "share revoked by member");
            } catch (Exception e) {
                LOGGER.warn("revoke share {}: access {} revoke failed: {}", shareId, share.getAccessId(), e.getMessage());
            }
        }
        share.setRevokedAt(OffsetDateTime.now());
        shareRepository.save(share);
    }

    /** Cascade: revoke every active share a member created (e.g. when their own access ends). */
    @Transactional
    public int revokeSharesBySharer(Long sharerContactId, String reason) {
        int n = 0;
        for (BeKeyShare s : shareRepository.findBySharerContactIdAndRevokedAtIsNullOrderByCreatedAtDesc(sharerContactId)) {
            try {
                if (s.getAccessId() != null) {
                    accessService.revoke(s.getAccessId(), reason);
                }
                s.setRevokedAt(OffsetDateTime.now());
                shareRepository.save(s);
                n++;
            } catch (Exception e) {
                LOGGER.warn("cascade revoke of share {} failed: {}", s.getId(), e.getMessage());
            }
        }
        if (n > 0) {
            LOGGER.info("Cascade-revoked {} BeKey shares for sharer {} ({})", n, sharerContactId, reason);
        }
        return n;
    }

    /** Finds the guest contact by email, or creates a lightweight 'Invitado' one. */
    private Long resolveOrCreateGuestContact(String email, String name) {
        Optional<ContactProfile> existing = contactProfileRepository
                .findFirstByEmailPrimaryIgnoreCaseOrEmailSecondaryIgnoreCaseOrEmailTertiaryIgnoreCaseOrRepresentativeEmailIgnoreCase(
                        email, email, email, email);
        if (existing.isPresent()) {
            return existing.get().getId();
        }
        ContactProfile cp = new ContactProfile();
        cp.setId(System.currentTimeMillis());   // contact_profiles.id is manually assigned
        cp.setName((name != null && !name.isBlank()) ? name.trim() : email);
        cp.setEmailPrimary(email);
        cp.setStatus("Inactivo");
        cp.setTenantType("Invitado");
        cp.setCreatedAt(LocalDateTime.now());
        cp.setStatusChangedAt(LocalDateTime.now());
        cp.setChannel("BeKey-guest-share");
        contactProfileRepository.save(cp);
        LOGGER.info("Created guest contact {} for BeKey share ({})", cp.getId(), email);
        return cp.getId();
    }
}
