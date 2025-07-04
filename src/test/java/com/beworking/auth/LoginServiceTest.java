package com.beworking.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;


import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class LoginServiceTest {
    @Mock
    private UserRepository userRepository;

    @Mock 
    private PasswordEncoder passwordEncoder;

    @InjectMocks
    private LoginService loginService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    /**
     * Test for successful login with valid credentials.
     * It verifies that the user is returned when the email and password match.
     */
    @Test
    void authenticate_SucessfullLogin_ReturnsUser() {
        String email = "user@example.com";
        String password = "correctPassword";
        String hashedPassword = "hashedPassword";
        User user = new User();
        user.setEmail(email);
        user.setPassword(hashedPassword);

        when(userRepository.findByEmail(email)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches(password, hashedPassword)).thenReturn(true);

        Optional<User> result = loginService.authenticate(email, password);

        assertTrue(result.isPresent());
        assertEquals(user, result.get());
    }

}