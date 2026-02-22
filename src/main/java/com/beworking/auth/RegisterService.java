package com.beworking.auth;

import com.beworking.contacts.ContactProfile;
import com.beworking.contacts.ContactProfileRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * Handles self-service user registration, confirmation, and password reset flows.
 */
@Service
public class RegisterService {
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final EmailService emailService;
    private final ContactProfileRepository contactProfileRepository;

    public RegisterService(UserRepository userRepository, PasswordEncoder passwordEncoder,
                           EmailService emailService, ContactProfileRepository contactProfileRepository) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.emailService = emailService;
        this.contactProfileRepository = contactProfileRepository;
    }

    /**
     * Creates a new user account when inputs are valid and the email is unused.
     */
    public boolean registerUser(String name, String email, String password) {
        // Validate required inputs and password complexity before any I/O.
        if (!isNonBlank(name) || !isNonBlank(email) || !isPasswordValid(password)) {
            return false;
        }

        String normalizedEmail = email.toLowerCase().trim();

        // Enforce unique email address.
        if (userRepository.findByEmail(normalizedEmail).isPresent()) {
            return false;
        }

        User user = new User(normalizedEmail, passwordEncoder.encode(password), User.Role.USER);
        user.setName(name.trim());
        user.setEmailConfirmed(false);
        String rawToken = UUID.randomUUID().toString();
        user.setConfirmationToken(hashToken(rawToken));
        user.setConfirmationTokenExpiry(Instant.now().plus(24, ChronoUnit.HOURS));

        // Auto-link to existing contact profile, or create a new one
        var existingProfile = contactProfileRepository
            .findFirstByEmailPrimaryIgnoreCaseOrEmailSecondaryIgnoreCaseOrEmailTertiaryIgnoreCaseOrRepresentativeEmailIgnoreCase(
                normalizedEmail, normalizedEmail, normalizedEmail, normalizedEmail);
        if (existingProfile.isPresent()) {
            user.setTenantId(existingProfile.get().getId());
        } else {
            ContactProfile cp = new ContactProfile();
            cp.setId(System.currentTimeMillis());
            cp.setName(name.trim());
            cp.setEmailPrimary(normalizedEmail);
            cp.setStatus("Potencial");
            cp.setActive(true);
            cp.setCreatedAt(LocalDateTime.now());
            cp.setStatusChangedAt(LocalDateTime.now());
            cp.setChannel("Self-registration");
            contactProfileRepository.save(cp);
            user.setTenantId(cp.getId());
        }

        userRepository.save(user);

        // Fire-and-forget confirmation email after persistence.
        emailService.sendConfirmationEmail(email, rawToken);
        return true;
    }

    /**
     * Retrieves a user awaiting confirmation by a one-time token.
     */
    public java.util.Optional<User> findByConfirmationToken(String token) {
        return token == null ? java.util.Optional.empty() : userRepository.findByConfirmationToken(hashToken(token));
    }

    public void saveUser(User user) {
        userRepository.save(user);
    }

    public boolean sendPasswordResetEmail(String email) {
        if (email != null) {
            email = email.toLowerCase().trim();
        }
        var userOpt = userRepository.findByEmail(email);
        if (userOpt.isEmpty()) {
            return false;
        }
        User user = userOpt.get();
        String token = UUID.randomUUID().toString();
        user.setConfirmationToken(hashToken(token));
        user.setConfirmationTokenExpiry(Instant.now().plus(1, ChronoUnit.HOURS));
        userRepository.save(user);
        emailService.sendPasswordResetEmail(email, token);
        return true;
    }

    /**
     * Resets a password when the token is valid, unexpired, and meets complexity.
     */
    public boolean resetPassword(String token, String newPassword) {
        if (!isPasswordValid(newPassword)) {
            return false;
        }
        var userOpt = token == null ? java.util.Optional.<User>empty() : userRepository.findByConfirmationToken(hashToken(token));
        if (userOpt.isEmpty()) {
            return false;
        }
        User user = userOpt.get();
        if (user.getConfirmationTokenExpiry() == null || user.getConfirmationTokenExpiry().isBefore(Instant.now())) {
            return false;
        }
        String hashedPassword = passwordEncoder.encode(newPassword);
        user.setPassword(hashedPassword);
        user.setConfirmationToken(null);
        user.setConfirmationTokenExpiry(null);
        userRepository.save(user);
        return true;
    }

    private boolean isNonBlank(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private boolean isPasswordValid(String password) {
        // Enforce minimum length and basic complexity without over-constraining users.
        return password != null
                && password.length() >= 8
                && password.matches(".*[a-z].*")
                && password.matches(".*[A-Z].*")
                && password.matches(".*\\d.*")
                && password.matches(".*[!@#$%^&*()_+\\-=\\[\\]{};':\"\\\\|,.<>\\/?].*");
    }

    private String hashToken(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(token.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hashed) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
