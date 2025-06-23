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

    /**
     * Test registration with a duplicate email.
     * Simulates trying to register a user with an email that already exists.
     * Expects registration to fail and no user to be saved
     */

    @Test
    void testRegisterUser_DuplicateEmail() {
        String name = "Test User";
        String email = "test@example.com";
        String password = "Password123!";
        
        // Simulate exisiting user with the same email
        when(userRepository.findByEmail(email)).thenReturn(Optional.of(new User()));

        // Attempt to register with duplicate email
        boolean result = registerService.registerUser(name, email, password);
        // Assert that registration fails
        assertFalse (result);

        // Verify that no user was saved and no confirmation email was sent
        verify(userRepository, never()).save(any(User.class));
        verify(emailService, never()).sendConfirmationEmail(anyString(), anyString());
    }

    /**
     * Test registration with null or empty name, email, or password.
     * Expects registration to fail and no user to be saved.
     */

    @Test 
    void testRegisterUser_NullOrEmptyInput() {
        // Null name
        assertFalse(registerService.registerUser(null, "test@example.com", "Password123!"));
        // Null email
        assertFalse(registerService.registerUser("Test User",null, "Password123!"));
        // Null password
        assertFalse(registerService.registerUser("Test User", "test@example.com", null));
        // Empty name
        assertFalse(registerService.registerUser("", "test@example.com","Password123!"));
        // Empty email
        assertFalse(registerService.registerUser("Test User", "", "Password123!"));
        // Empty password
        assertFalse(registerService.registerUser("Test User", "test@example.com", ""));
        verify(userRepository, never()).save(any(User.class));
        verify(emailService, never()).sendConfirmationEmail(anyString(), anyString());

    }

    /**
     * Test registration with a weak password.
     * Expects registration to fail and no user to be saved or email sent.
     * Password must be at least 8 characters long and contain upper, lower, number,
     * and special characters.
     */

     @Test 
     void testRegisterUser_WeakPassword() {
        String name = "Test User";
        String email = "test@example.com";

        // Too short
        assertFalse(registerService.registerUser(name, email, "P1!a"));
        // No upper case
        assertFalse(registerService.registerUser(name, email, "password123!"));
        // No lower case
        assertFalse(registerService.registerUser(name, email, "PASSWORD234!"));
        // No digit
        assertFalse(registerService.registerUser(name, email, "Password!"));
        // No sepecial character
        assertFalse(registerService.registerUser(name, email, "Password123"));
        verify(userRepository, never()).save(any(User.class));
        verify(emailService, never()).sendConfirmationEmail(anyString(), anyString());
     }

}
