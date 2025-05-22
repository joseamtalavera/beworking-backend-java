package com.beworking.auth;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class RegisterService {
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public RegisterService(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    public boolean registerUser(String name, String email, String password) {
        System.out.println("Registering user: " + email);
        if (userRepository.findByEmail(email).isPresent()) {
            System.out.println("User already exists: " + email);
            return false; // User already exists
        }
        String hashedPassword = passwordEncoder.encode(password);
        User user = new User(email, hashedPassword, User.Role.USER);
        userRepository.save(user);
        System.out.println("User registered successfully: " + email);
        return true;
    }
}