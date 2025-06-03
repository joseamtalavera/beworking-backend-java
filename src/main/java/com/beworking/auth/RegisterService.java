package com.beworking.auth;

import org.springframework.security.crypto.password.PasswordEncoder;
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
}