package com.beworking.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.MockitoAnnotations;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.Optional;

/**
 * Unit tests for the RegisterService class.
 * These tests verify the registration logic, including sucess, duplicate email, and passowrds.
 * Mock are used for dependencies like UserRepository, PasswordEncoder, and EmailService.
 */

@ExtendWith(MockitoExtension.class)
class RegisterServiceTest {
    
    // Mock dependencies
    @Mock
    private UserRepository userRepository;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private EmailService emailService;

    // Service under test
    private RegisterService registerService;

    /**
     * This method runs before each test to initialize the RegisterService with mocked dependencies.
     */
    @BeforeEach
    void setUp() {
        registerService = new RegisterService(userRepository, passwordEncoder, emailService);
    }

    /**
     * Test for successful user registration.
     * Simulates a new user registering with a valid email and password.
     * Expects the user to be saved, password to be hashed, and a confirmation email to be sent.
     * use ./mvnw test to run the tests.
     */
    @Test
    void testRegisterUser_Success() {
        String name = "Test User";
        String email = "test@example.com";
        String password = "Password123!";
        when(userRepository.findByEmail(email)).thenReturn(java.util.Optional.empty());
        when(passwordEncoder.encode(password)).thenReturn("hashed");
        when(userRepository.save(any(User.class))).thenReturn(new User());
        boolean result = registerService.registerUser(name, email, password);
        assertTrue(result);
        verify(emailService).sendConfirmationEmail(eq(email), anyString());
    }
}
