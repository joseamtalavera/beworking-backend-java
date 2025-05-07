package com.beworking.auth;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import java.util.Optional;

@Service
public class AuthService {
    private final UserRepository userRepository; // it contains the UserRepository interface. It is a part of Spring Data JPA and provides methods for CRUD operations and pagination.
    private final PasswordEncoder passwordEncoder; // it contains the PasswordEncoder interface. It is used to encode and decode passwords.

    // Constructor injection (no @Autowired needed)
    public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    public Optional<User> authenticate(String email, String password, User.Role requiredRole) { //
        Optional<User> userOpt = userRepository.findByEmail(email);
        if (userOpt.isPresent()) {
            User user = userOpt.get(); // it retrieves the value from the Optional object. If the Optional is empty, it throws NoSuchElementException.
            if (passwordEncoder.matches(password, user.getPassword()) && user.getRole() == requiredRole) {
                return Optional.of(user); // it returns an Optional object containing the user if the password matches and the role is correct.
            }
        }
        return Optional.empty();
    }
}
