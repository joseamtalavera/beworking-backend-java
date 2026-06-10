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
import org.springframework.scheduling.annotation.Scheduled;
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
 * Rules: the sharer picks any window they like; its upper bound is their own
 * grant's expiry (a share can't outlive the access it borrows from). No cap on
 * how many people a member shares with. Only a member who holds real
 * (non-shared) access may share, and they share the group they hold — a guest
 * on a {@code source=shared} grant therefore cannot re-share.
 */
@Service
public class BeKeyShareService {

    private static final Logger LOGGER = LoggerFactory.getLogger(BeKeyShareService.class);

    private final BeKeyShareRepository shareRepository;
    private final BeKeyAccessService accessService;
    private final BeKeyAccessRepository accessRepository;
    private final ContactProfileRepository contactProfileRepository;
    private final RegisterService registerService;
    private final UserRepository userRepository;
    private final EmailService emailService;
    private final boolean integrationEnabled;
    private final String frontendUrl;

    public BeKeyShareService(BeKeyShareRepository shareRepository,
                             BeKeyAccessService accessService,
                             BeKeyAccessRepository accessRepository,
                             ContactProfileRepository contactProfileRepository,
                             RegisterService registerService,
                             UserRepository userRepository,
                             EmailService emailService,
                             @Value("${akiles.integration.enabled:false}") boolean integrationEnabled,
                             @Value("${app.frontend-url}") String frontendUrl) {
        this.shareRepository = shareRepository;
        this.accessService = accessService;
        this.accessRepository = accessRepository;
        this.contactProfileRepository = contactProfileRepository;
        this.registerService = registerService;
        this.userRepository = userRepository;
        this.emailService = emailService;
        this.integrationEnabled = integrationEnabled;
        this.frontendUrl = frontendUrl;
    }

    /**
     * What a freshly-created share hands back: the share row plus the materials
     * the sharer needs to deliver the invite over WhatsApp themselves — the
     * guest's entry URL and a ready-to-send bilingual message. {@code whatsappUrl}
     * is a wa.me deep link (targeted at the guest's number when provided).
     */
    public record ShareResult(BeKeyShare share, String inviteUrl, String whatsappText, String whatsappUrl) {}

    /** Active (non-revoked) shares a member created, newest first. */
    @Transactional(readOnly = true)
    public List<BeKeyShare> listForSharer(Long sharerContactId) {
        return shareRepository.findBySharerContactIdAndRevokedAtIsNullOrderByCreatedAtDesc(sharerContactId);
    }

    @Transactional
    public ShareResult createShare(Long sharerContactId, String guestName, String guestEmail,
                                   OffsetDateTime startsAt, OffsetDateTime endsAt,
                                   String channel, String guestPhone) {
        if (!integrationEnabled) {
            throw new IllegalStateException("BeKey integration disabled");
        }
        if (guestEmail == null || guestEmail.isBlank()) {
            throw new IllegalArgumentException("Guest email is required");
        }
        final String email = guestEmail.trim().toLowerCase();
        // "email" (default) | "whatsapp" | "both". WhatsApp delivery is handled by
        // the sharer (we hand back a wa.me link); email is sent server-side.
        final String ch = (channel == null || channel.isBlank()) ? "email" : channel.trim().toLowerCase();
        final boolean sendEmail = !"whatsapp".equals(ch);
        final boolean wantsWhatsapp = "whatsapp".equals(ch) || "both".equals(ch);

        // 1. The sharer must hold real (non-shared) access — share the group they
        //    hold. A guest on a source=shared grant has none here, so can't re-share.
        BeKeyAccess base = accessRepository.findByContactIdAndRevokedAtIsNull(sharerContactId).stream()
                .filter(g -> g.getSource() != BeKeyAccess.Source.shared)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("You have no access to share"));
        Long memberGroupId = base.getMemberGroupId();

        // 2. Validate the window (required, future). The end is clamped to the
        //    sharer's own grant expiry — a share can't outlive its borrowed access.
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
        OffsetDateTime end = endsAt;
        if (base.getEndsAt() != null && end.isAfter(base.getEndsAt())) {
            end = base.getEndsAt();   // clamp to the sharer's own access expiry
        }
        if (!end.isAfter(start)) {
            throw new IllegalStateException("Your own access ends before that window starts");
        }

        // 3. Resolve sharer (for the email + self-share guard) and the guest contact.
        ContactProfile sharer = contactProfileRepository.findById(sharerContactId).orElse(null);
        if (sharer != null && email.equalsIgnoreCase(sharer.getEmailPrimary())) {
            throw new IllegalArgumentException("You can't share access with yourself");
        }
        Long guestContactId = resolveOrCreateGuestContact(email, guestName);

        // 4. Free guest account; only NEW accounts get a password-setup link.
        boolean newUser = userRepository.findByEmail(email).isEmpty();
        User guestUser = registerService.createUserForContactSilent(email, guestName, guestContactId);
        String setupToken = (newUser && guestUser != null)
                ? registerService.generatePasswordSetupToken(guestUser.getId(), Duration.ofDays(7))
                : null;

        // 5. Persist the share, spawn the grant, link them.
        BeKeyShare share = new BeKeyShare();
        share.setSharerContactId(sharerContactId);
        share.setGuestContactId(guestContactId);
        share.setGuestEmail(email);
        share.setGuestName(guestName);
        share.setMemberGroupId(memberGroupId);
        share.setStartsAt(start);
        share.setEndsAt(end);
        share = shareRepository.save(share);

        BeKeyAccess access = accessService.grant(
                guestContactId, memberGroupId, BeKeyAccess.Source.shared, share.getId(), start, end);
        if (access == null) {
            throw new IllegalStateException("Could not create the access grant");
        }
        share.setAccessId(access.getId());
        share = shareRepository.save(share);

        String sharerName = (sharer != null && sharer.getName() != null) ? sharer.getName() : "Un miembro de BeWorking";

        // 6. Deliver the invite. Email is sent server-side unless this is a
        //    WhatsApp-only share; the guest's entry link is the same either way:
        //    a password-setup URL for new accounts, login for existing ones.
        if (sendEmail) {
            try {
                emailService.sendBeKeyShareInvite(email, guestName, sharerName, start, end, setupToken);
            } catch (Exception e) {
                LOGGER.warn("BeKey share invite email failed for {}: {}", email, e.getMessage());
            }
        }

        // 7. WhatsApp materials: the sharer sends these from their own phone.
        boolean needsSetup = setupToken != null && !setupToken.isBlank();
        String inviteUrl = needsSetup
                ? frontendUrl + "/reset-password?token=" + setupToken
                : frontendUrl + "/login";
        String whatsappText = null;
        String whatsappUrl = null;
        if (wantsWhatsapp) {
            whatsappText = buildWhatsappText(sharerName, guestName, start, end, inviteUrl, needsSetup);
            whatsappUrl = buildWhatsappUrl(guestPhone, whatsappText);
        }

        LOGGER.info("BeKey share {} created: sharer {} -> guest {} on group {} ({} .. {}) via {}",
                share.getId(), sharerContactId, guestContactId, memberGroupId, start, end, ch);
        return new ShareResult(share, inviteUrl, whatsappText, whatsappUrl);
    }

    /** Ready-to-send bilingual WhatsApp invite: window + entry link + what to do. */
    private String buildWhatsappText(String sharerName, String guestName,
                                     OffsetDateTime start, OffsetDateTime end,
                                     String inviteUrl, boolean needsSetup) {
        java.util.Locale es = new java.util.Locale("es", "ES");
        java.time.format.DateTimeFormatter fmt = java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", es);
        java.time.ZoneId madrid = java.time.ZoneId.of("Europe/Madrid");
        String startStr = start.atZoneSameInstant(madrid).format(fmt);
        String endStr = end.atZoneSameInstant(madrid).format(fmt);
        String hi = (guestName != null && !guestName.isBlank()) ? "Hola " + guestName.trim() + "," : "Hola,";
        String action = needsSetup
                ? "Pulsa el botón para crear tu contraseña (cuenta gratuita) y abrir la puerta desde la app."
                : "Entra en la app con tu cuenta para abrir la puerta.";
        String actionEn = needsSetup
                ? "Tap the button to set your password (free account) and open the door from the app."
                : "Log in to the app to open the door.";
        return hi + " " + sharerName + " te ha dado acceso a las puertas de BeWorking.\n"
                + "Válido del " + startStr + " al " + endStr + ".\n"
                + action + "\n" + inviteUrl + "\n\n"
                + "— — —\n"
                + sharerName + " gave you BeWorking door access (" + startStr + " – " + endStr + "). "
                + actionEn;
    }

    /** wa.me deep link; targets the guest's number when given, else opens the picker. */
    private String buildWhatsappUrl(String guestPhone, String text) {
        String encoded = java.net.URLEncoder.encode(text, java.nio.charset.StandardCharsets.UTF_8);
        String digits = (guestPhone == null) ? "" : guestPhone.replaceAll("[^0-9]", "");
        return digits.isEmpty()
                ? "https://wa.me/?text=" + encoded
                : "https://wa.me/" + digits + "?text=" + encoded;
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

    /**
     * Sweep expired shares every 10 minutes: revoke the underlying grant (which
     * removes the Akiles association) and mark the share revoked, so it drops off
     * the sharer's list and the guest's access is cleaned up. Door-open authz
     * already refuses out-of-window grants, so this is for state hygiene rather
     * than security — but it keeps the DB and Akiles in sync.
     */
    @Scheduled(cron = "0 5,15,25,35,45,55 * * * *")
    @Transactional
    public void sweepExpiredShares() {
        if (!integrationEnabled) {
            return;
        }
        List<BeKeyShare> expired = shareRepository.findByRevokedAtIsNullAndEndsAtBefore(OffsetDateTime.now());
        int n = 0;
        for (BeKeyShare s : expired) {
            try {
                if (s.getAccessId() != null) {
                    accessService.revoke(s.getAccessId(), "share window ended");
                }
                s.setRevokedAt(OffsetDateTime.now());
                shareRepository.save(s);
                n++;
            } catch (Exception e) {
                LOGGER.warn("sweep of expired share {} failed: {}", s.getId(), e.getMessage());
            }
        }
        if (n > 0) {
            LOGGER.info("BeKey expiry sweep: revoked {} expired share(s)", n);
        }
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
