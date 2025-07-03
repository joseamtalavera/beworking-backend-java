package com.beworking.auth;

import org.hibernate.annotations.TimeZoneStorage;
import org.springframework.security.crypto.password.PasswordEncoder; // PasswordEncoder interface for encoding passwords
import org.springframework.stereotype.Service;
import java.util.UUID; 
import java.time.Instant; 
import java.time.temporal.ChronoUnit;

@Service
public class RegisterService {
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final EmailService emailService;

    public RegisterService(UserRepository userRepository, PasswordEncoder passwordEncoder, EmailService emailService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.emailService = emailService;
    }

    public boolean registerUser(String name, String email, String password) {
        System.out.println("Registering user: " + email);
        if (userRepository.findByEmail(email).isPresent()) {
            System.out.println("User already exists: " + email);
            return false; // User already exists
        }
        // Password complexity: min 8 chars, upper, lower, number, symbol
        if (name == null || name.isEmpty() || email == null || email.isEmpty() || password == null || password.isEmpty() ||
            password.length() < 8 ||
            !password.matches(".*[a-z].*") ||
            !password.matches(".*[A-Z].*") ||
            !password.matches(".*\\d.*") ||
            !password.matches(".*[!@#$%^&*()_+\\-=\\[\\]{};':\"\\\\|,.<>\\/?].*")) {
            System.out.println("Password does not meet complexity requirements");
            return false;
        }
        String hashedPassword = passwordEncoder.encode(password);
        User user = new User(email, hashedPassword, User.Role.USER);
        user.setEmailConfirmed(false);
        user.setConfirmationToken(UUID.randomUUID().toString());
        user.setConfirmationTokenExpiry(Instant.now().plus(24, ChronoUnit.HOURS));
        userRepository.save(user);
        System.out.println("User registered successfully: " + email);
        // Send confirmation email
        emailService.sendConfirmationEmail(email, user.getConfirmationToken());
        return true;
    }

    public java.util.Optional<User> findByConfirmationToken(String token) {
        return userRepository.findAll().stream()
            .filter(u -> token != null && token.equals(u.getConfirmationToken()))
            .findFirst();
    }

    public void saveUser(User user) {
        userRepository.save(user);
    }

    public boolean sendPasswordResetEmail(String email) {
        var userOpt = userRepository.findByEmail(email);
        if (userOpt.isEmpty()) {
            return false;
        }
        User user = userOpt.get();
        String token = UUID.randomUUID().toString();
        user.setConfirmationToken(token);
        user.setConfirmationTokenExpiry(Instant.now().plus(1, ChronoUnit.HOURS));
        userRepository.save(user);
        emailService.sendPasswordResetEmail(email, token);
        return true;
    }

    public boolean resetPassword(String token, String newPassword) {
        // Password complexity: min 8 chars, upper, lower, number, symbol
        if (newPassword == null || newPassword.length() < 8 ||
            !newPassword.matches(".*[a-z].*") ||
            !newPassword.matches(".*[A-Z].*") ||
            !newPassword.matches(".*\\d.*") ||
            !newPassword.matches(".*[!@#$%^&*()_+\\-=\\[\\]{};':\"\\\\|,.<>\\/?].*")) {
            System.out.println("Password does not meet complexity requirements");
            return false;
        }
        var userOpt = userRepository.findAll().stream()
            .filter(u -> token != null && token.equals(u.getConfirmationToken()))
            .findFirst();
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
}