package com.beworking.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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
    @Mock
    private com.beworking.contacts.ContactProfileRepository contactProfileRepository;

    // Service under test
    private RegisterService registerService;

    /**
     * This method runs before each test to initialize the RegisterService with mocked dependencies.
     */
    @BeforeEach
    void setUp() {
        registerService = new RegisterService(userRepository, passwordEncoder, emailService, contactProfileRepository);
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
     * Expects registration to fail for weak passorod.
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

     /**
      * Test registration with minimum password (8 characters, upper, lower, number, symbol).
      * Expects registration to scucceed for strong password.
      * This test checks that the password meets the minimum requirements.
      */

      @Test 
      void testRegisterUser_MinimumValidPassword(){
        String name = "Test User";
        String email = "test@example.com";
        String password = "Aa1!aaaa";
        when(userRepository.findByEmail(email)).thenReturn(Optional.empty());
        when(passwordEncoder.encode(password)).thenReturn("hashed");
        when(userRepository.save(any(User.class))).thenReturn(new User());
        boolean result = registerService.registerUser(name, email, password);
        assertTrue(result);
        verify(emailService).sendConfirmationEmail(eq(email), anyString());
      }

    /**
     * test for confirmation to
     */
    @Test 
    void testRegisterUser_ConfirmationTokenAndExpirySet(){
        String name = "Test User";
        String email = "test@example.com";
        String password = "Password123!";
        when(userRepository.findByEmail(email)).thenReturn(Optional.empty());
        when(passwordEncoder.encode(password)).thenReturn("hashed");
        final User[] savedUser = new User[1];
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> {
            savedUser[0] = invocation.getArgument(0);
            return savedUser[0];
        });
        boolean result = registerService.registerUser(name, email, password);
        assertTrue(result);
        assertNotNull(savedUser[0]);
        assertNotNull(savedUser[0].getConfirmationToken());
        assertNotNull(savedUser[0].getConfirmationTokenExpiry());

    }

    /**
     * Test that confirm that the email is sent with the correct token.
     */
    @Test
    void testRegisterUser_ConfirmationEmailContent() {
        String name = "Test User";
        String email = "test@example.com";
        String password = "Password123!";
        when(userRepository.findByEmail(email)).thenReturn(Optional.empty());
        when(passwordEncoder.encode(password)).thenReturn("hashed");
        final User[] savedUser = new User[1];
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> {
            savedUser[0] = invocation.getArgument(0);
            return savedUser[0];
        });
        boolean result = registerService.registerUser(name, email, password);
        assertTrue(result); // Add this line to assert registration succeeded
        // verify that the confirmation email was sent with the correct token
        verify(emailService).sendConfirmationEmail(eq(email), eq(savedUser[0].getConfirmationToken()));
    }

    /**
     * This test checks if the confirmation token is found in the user repository.
     * It simulates a scenario where a user has a confirmation token and verifies that the
     * service can find the user by that token.
     */

     @Test
     void testRegisterUser_ConfirmationToken_Found() {
        String token ="abc123";
        User user = new User("test@example.com", "hashed", User.Role.USER);
        user.setConfirmationToken(token);
        when(userRepository.findAll()).thenReturn(java.util.List.of(user));

        Optional<User> result = registerService.findByConfirmationToken(token);
        assertTrue(result.isPresent());
        assertEquals(token, result.get().getConfirmationToken());
     }

     @Test
     void testRegisterUser_ConfirmationToken_NotFound(){
        String token = "notfound";
        when(userRepository.findAll()).thenReturn(java.util.List.of());
        Optional<User> result = registerService.findByConfirmationToken(token);
        assertTrue(result.isEmpty());
     }

     /**
      * test that the saveUser method saves the user to the repository.
      */

     @Test 
     void testSaveUser_SavesToRepository() {
        User user = new User("test@example.com", "hashed", User.Role.USER);
        registerService.saveUser(user);
        verify(userRepository).save(user);
     }

     /**
      * Test for sending a password reset email.
      * This test verifes:
       * - The user exists in the repository.
       * - A reset token is generated and set on the user.
       * - The user is saved with the new token and expiry.
       * - The password reset email is sent with the correct token.
      */
      @Test
      void testSendPasswordResetEmail_UserExists() {
        String email = "reset@example.com";
        User user = new User(email, "hashed", User.Role.USER);
        when(userRepository.findByEmail(email)).thenReturn(Optional.of(user));
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> { return invocation.getArgument(0); });

        boolean result = registerService.sendPasswordResetEmail(email);

        assertTrue(result, "Should return true when user exists");
        assertNotNull(user.getConfirmationToken(), "Confirmation token should not be null");
        assertNotNull(user.getConfirmationTokenExpiry(), "Token expiry should not be null");
        verify(userRepository).save(user);
        verify(emailService).sendPasswordResetEmail(eq(email), eq(user.getConfirmationToken()));
      }

        /**
         * Test for sending a password reset email when the user does NOT exist.
         * This test verifies that:
         * - The method returns false
         * - No user is saved
         * - No email is sent
         */

        @Test
        void testSendPasswordResetEmail_UserDoesNotExist() {
            String email = "notfound@example.com";
            when(userRepository.findByEmail(email)).thenReturn(Optional.empty());

            boolean result = registerService.sendPasswordResetEmail(email);

            assertFalse(result, "Should return false when user does not exist");
            verify(userRepository, never()).save(any(User.class));
            verify(emailService, never()).sendPasswordResetEmail(anyString(), anyString());
        }

        /**
        * Test resetPassword method with a weak password.
        * This should fail since the password does not meet complexity requirements.
        */

         @Test
        void testResetPassword_WeakOrNullPassword() {
            assertFalse(registerService.resetPassword( "token", null));
            assertFalse(registerService.resetPassword("token", "P1!a"));
            assertFalse(registerService.resetPassword("token", "password123!"));
            assertFalse(registerService.resetPassword("token", "PASSWORD234!"));
            assertFalse(registerService.resetPassword("token", "Password!"));
            assertFalse(registerService.resetPassword("token", "Password123"));
            verify(userRepository, never()).save(any(User.class));
        }

        /**
         * Test resestPassword method when no user is found with the given token.
         * This should return false and not save any user.
         */
        @Test
        void testResetPassword_UserNotFound() {
            when(userRepository.findAll()).thenReturn(java.util.List.of());
            assertFalse(registerService.resetPassword("notfoundtoken", "Password123!"));
            verify(userRepository, never()).save(any(User.class));
        }

        /**
         * Test resetPassword method when the token is expired.
         * This should return false and not save any user.
         */
        @Test
        void testResetPassword_TokenExpired() {
            User user = new User("test@example.com", "hashed", User.Role.USER);
            user.setConfirmationToken("validtoken");
            user.setConfirmationTokenExpiry(java.time.Instant.now().minus(2, java.time.temporal.ChronoUnit.HOURS));
            when(userRepository.findAll()).thenReturn(java.util.List.of(user));
            assertFalse(registerService.resetPassword("validtoken", "Password123!"));
            verify(userRepository, never()).save(any(User.class));
        }
        /**
         * Test sucessful password reset.
         * This test expects to update the user's password, clear token, save the user, and return true.
         */
        @Test
        void testResetPassword_Success() {
            User user = new User( "test@example.com", "hashed", User.Role.USER);
            user.setConfirmationToken("validtoken");
            user.setConfirmationTokenExpiry(java.time.Instant.now().plus(2, java.time.temporal.ChronoUnit.HOURS));
            when(userRepository.findAll()).thenReturn(java.util.List.of(user));
            when(passwordEncoder.encode("Valid1!pass")).thenReturn("newhashed");
            
            boolean result = registerService.resetPassword("validtoken", "Valid1!pass");

            assertTrue(result);
            assertEquals("newhashed", user.getPassword());
            assertNull(user.getConfirmationToken());
            assertNull(user.getConfirmationTokenExpiry());
            verify(userRepository).save(user);
        }

     
}
