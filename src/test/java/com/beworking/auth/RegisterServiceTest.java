package com.beworking.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.Optional;

@ExtendWith(MockitoExtension.class)
class RegisterServiceTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private EmailService emailService;
    @Mock
    private com.beworking.contacts.ContactProfileRepository contactProfileRepository;
    @Mock
    private com.beworking.subscriptions.SubscriptionRepository subscriptionRepository;
    @Mock
    private com.beworking.plans.PlanRepository planRepository;

    private RegisterService registerService;

    @BeforeEach
    void setUp() {
        registerService = new RegisterService(userRepository, passwordEncoder, emailService, contactProfileRepository, subscriptionRepository, planRepository);
    }

    private RegisterRequest makeRequest(String name, String email, String password) {
        RegisterRequest r = new RegisterRequest();
        r.setName(name);
        r.setEmail(email);
        r.setPassword(password);
        return r;
    }

    @Test
    void testRegisterUserWithTrial_Success() {
        String name = "Test User";
        String email = "test@example.com";
        String password = "Password123!";
        when(userRepository.findByEmail(email)).thenReturn(Optional.empty());
        when(contactProfileRepository.findFirstByEmailPrimaryIgnoreCaseOrEmailSecondaryIgnoreCaseOrEmailTertiaryIgnoreCaseOrRepresentativeEmailIgnoreCase(
            anyString(), anyString(), anyString(), anyString())).thenReturn(Optional.empty());
        when(passwordEncoder.encode(password)).thenReturn("hashed");
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        Map<String, Object> result = registerService.registerUserWithTrial(makeRequest(name, email, password));

        assertNotNull(result);
        User user = (User) result.get("user");
        assertTrue(user.isEmailConfirmed());
        verify(userRepository).save(any(User.class));
        verify(contactProfileRepository).save(any());
    }

    @Test
    void testRegisterUserWithTrial_DuplicateEmail() {
        when(userRepository.findByEmail("test@example.com")).thenReturn(Optional.of(new User()));

        assertThrows(IllegalStateException.class, () ->
            registerService.registerUserWithTrial(makeRequest("Test User", "test@example.com", "Password123!"))
        );
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void testRegisterUserWithTrial_NullOrEmptyInput() {
        assertThrows(IllegalArgumentException.class, () -> registerService.registerUserWithTrial(makeRequest(null, "test@example.com", "Password123!")));
        assertThrows(IllegalArgumentException.class, () -> registerService.registerUserWithTrial(makeRequest("Test User", null, "Password123!")));
        assertThrows(IllegalArgumentException.class, () -> registerService.registerUserWithTrial(makeRequest("Test User", "test@example.com", null)));
        assertThrows(IllegalArgumentException.class, () -> registerService.registerUserWithTrial(makeRequest("", "test@example.com", "Password123!")));
        assertThrows(IllegalArgumentException.class, () -> registerService.registerUserWithTrial(makeRequest("Test User", "", "Password123!")));
        assertThrows(IllegalArgumentException.class, () -> registerService.registerUserWithTrial(makeRequest("Test User", "test@example.com", "")));
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void testRegisterUserWithTrial_WeakPassword() {
        String name = "Test User";
        String email = "test@example.com";

        assertThrows(IllegalArgumentException.class, () -> registerService.registerUserWithTrial(makeRequest(name, email, "P1!a")));
        assertThrows(IllegalArgumentException.class, () -> registerService.registerUserWithTrial(makeRequest(name, email, "password123!")));
        assertThrows(IllegalArgumentException.class, () -> registerService.registerUserWithTrial(makeRequest(name, email, "PASSWORD234!")));
        assertThrows(IllegalArgumentException.class, () -> registerService.registerUserWithTrial(makeRequest(name, email, "Password!")));
        assertThrows(IllegalArgumentException.class, () -> registerService.registerUserWithTrial(makeRequest(name, email, "Password123")));
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void testRegisterUserWithTrial_MinimumValidPassword() {
        String email = "test@example.com";
        String password = "Aa1!aaaa";
        when(userRepository.findByEmail(email)).thenReturn(Optional.empty());
        when(contactProfileRepository.findFirstByEmailPrimaryIgnoreCaseOrEmailSecondaryIgnoreCaseOrEmailTertiaryIgnoreCaseOrRepresentativeEmailIgnoreCase(
            anyString(), anyString(), anyString(), anyString())).thenReturn(Optional.empty());
        when(passwordEncoder.encode(password)).thenReturn("hashed");
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        Map<String, Object> result = registerService.registerUserWithTrial(makeRequest("Test User", email, password));
        assertNotNull(result);
    }

    @Test
    void testRegisterUserWithTrial_CreatesSubscription() {
        String email = "test@example.com";
        String password = "Password123!";
        RegisterRequest request = makeRequest("Test User", email, password);
        request.setPlan("pro");

        when(userRepository.findByEmail(email)).thenReturn(Optional.empty());
        when(contactProfileRepository.findFirstByEmailPrimaryIgnoreCaseOrEmailSecondaryIgnoreCaseOrEmailTertiaryIgnoreCaseOrRepresentativeEmailIgnoreCase(
            anyString(), anyString(), anyString(), anyString())).thenReturn(Optional.empty());
        when(passwordEncoder.encode(password)).thenReturn("hashed");
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        Map<String, Object> result = registerService.registerUserWithTrial(request);
        assertNotNull(result);
        verify(subscriptionRepository).save(any(com.beworking.subscriptions.Subscription.class));
    }

    @Test
    void testConfirmationToken_Found() {
        String token = "abc123";
        User user = new User("test@example.com", "hashed", User.Role.USER);
        user.setConfirmationToken(token);
        when(userRepository.findByConfirmationToken(anyString())).thenReturn(Optional.of(user));

        Optional<User> result = registerService.findByConfirmationToken(token);
        assertTrue(result.isPresent());
    }

    @Test
    void testConfirmationToken_NotFound() {
        when(userRepository.findByConfirmationToken(anyString())).thenReturn(Optional.empty());
        Optional<User> result = registerService.findByConfirmationToken("notfound");
        assertTrue(result.isEmpty());
    }

    @Test
    void testSaveUser_SavesToRepository() {
        User user = new User("test@example.com", "hashed", User.Role.USER);
        registerService.saveUser(user);
        verify(userRepository).save(user);
    }

    @Test
    void testSendPasswordResetEmail_UserExists() {
        String email = "reset@example.com";
        User user = new User(email, "hashed", User.Role.USER);
        when(userRepository.findByEmail(email)).thenReturn(Optional.of(user));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        boolean result = registerService.sendPasswordResetEmail(email);

        assertTrue(result);
        assertNotNull(user.getConfirmationToken());
        assertNotNull(user.getConfirmationTokenExpiry());
        verify(userRepository).save(user);
        verify(emailService).sendPasswordResetEmail(eq(email), anyString());
    }

    @Test
    void testSendPasswordResetEmail_UserDoesNotExist() {
        when(userRepository.findByEmail("notfound@example.com")).thenReturn(Optional.empty());

        assertFalse(registerService.sendPasswordResetEmail("notfound@example.com"));
        verify(userRepository, never()).save(any(User.class));
        verify(emailService, never()).sendPasswordResetEmail(anyString(), anyString());
    }

    @Test
    void testResetPassword_WeakOrNullPassword() {
        assertFalse(registerService.resetPassword("token", null));
        assertFalse(registerService.resetPassword("token", "P1!a"));
        assertFalse(registerService.resetPassword("token", "password123!"));
        assertFalse(registerService.resetPassword("token", "PASSWORD234!"));
        assertFalse(registerService.resetPassword("token", "Password!"));
        assertFalse(registerService.resetPassword("token", "Password123"));
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void testResetPassword_UserNotFound() {
        when(userRepository.findByConfirmationToken(anyString())).thenReturn(Optional.empty());
        assertFalse(registerService.resetPassword("notfoundtoken", "Password123!"));
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void testResetPassword_TokenExpired() {
        User user = new User("test@example.com", "hashed", User.Role.USER);
        user.setConfirmationToken("validtoken");
        user.setConfirmationTokenExpiry(java.time.Instant.now().minus(2, java.time.temporal.ChronoUnit.HOURS));
        when(userRepository.findByConfirmationToken(anyString())).thenReturn(Optional.of(user));
        assertFalse(registerService.resetPassword("validtoken", "Password123!"));
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void testResetPassword_Success() {
        User user = new User("test@example.com", "hashed", User.Role.USER);
        user.setConfirmationToken("validtoken");
        user.setConfirmationTokenExpiry(java.time.Instant.now().plus(2, java.time.temporal.ChronoUnit.HOURS));
        when(userRepository.findByConfirmationToken(anyString())).thenReturn(Optional.of(user));
        when(passwordEncoder.encode("Valid1!pass")).thenReturn("newhashed");

        boolean result = registerService.resetPassword("validtoken", "Valid1!pass");

        assertTrue(result);
        assertEquals("newhashed", user.getPassword());
        assertNull(user.getConfirmationToken());
        assertNull(user.getConfirmationTokenExpiry());
        verify(userRepository).save(user);
    }
}
