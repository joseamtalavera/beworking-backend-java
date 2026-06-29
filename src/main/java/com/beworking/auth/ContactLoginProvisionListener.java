package com.beworking.auth;

import com.beworking.contacts.ContactProfile;
import com.beworking.contacts.ContactProfileCreatedEvent;
import com.beworking.contacts.ContactProfileRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Single guaranteed choke point for login provisioning.
 *
 * <p>Every in-app path that creates a {@link ContactProfile} publishes a
 * {@link ContactProfileCreatedEvent}. This listener ensures a matching
 * {@code users} row always exists, so a contact can never end up without a
 * login through application code — the recurring "credenciales inválidas / no
 * reset email" class of bug where a customer is Activo but has nothing to
 * authenticate against.
 *
 * <p>Idempotent: {@link RegisterService#createUserForContact} returns the
 * existing user untouched (and sends no email) when a login is already present,
 * so the self-signup / trial paths that create their own user with the
 * customer's chosen password are unaffected — no duplicate user, no double
 * email. Only paths that create a contact <em>without</em> a login (e.g. admin
 * "Crear contacto") actually get a user + welcome/set-password email here.
 *
 * <p>Runs AFTER_COMMIT in a fresh transaction (same proven pattern as
 * {@code LeadCleanupListener}) so the contact row is durably persisted first.
 * Failures are logged, never bubbled up; the forgot-password self-heal in
 * {@code RegisterService.sendPasswordResetEmail} remains the safety net for the
 * only thing this can't catch — direct DB/bulk imports that bypass the event.
 */
@Component
public class ContactLoginProvisionListener {

    private static final Logger logger = LoggerFactory.getLogger(ContactLoginProvisionListener.class);

    private final RegisterService registerService;
    private final ContactProfileRepository contactProfileRepository;

    public ContactLoginProvisionListener(RegisterService registerService,
                                         ContactProfileRepository contactProfileRepository) {
        this.registerService = registerService;
        this.contactProfileRepository = contactProfileRepository;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void provisionLogin(ContactProfileCreatedEvent event) {
        String email = event.email();
        if (email == null || email.isBlank()) {
            return; // no email -> nothing to authenticate with; nothing to provision
        }
        try {
            ContactProfile cp = contactProfileRepository.findById(event.contactProfileId()).orElse(null);
            String name = null;
            String avatar = null;
            if (cp != null) {
                name = cp.getContactName() != null && !cp.getContactName().isBlank()
                        ? cp.getContactName() : cp.getName();
                avatar = cp.getAvatar();
            }
            User user = registerService.createUserForContact(email, name, event.contactProfileId());
            if (user != null && avatar != null && user.getAvatar() == null) {
                user.setAvatar(avatar);
                registerService.saveUser(user);
            }
        } catch (Exception e) {
            logger.warn("Login provisioning failed for contact {} ({}): {}",
                    event.contactProfileId(), email, e.getMessage());
        }
    }
}
