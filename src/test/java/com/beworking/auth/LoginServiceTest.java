package com.beworking.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

public class LoginServiceTest {
    @Mock
    private UserRepository userRepository;
    @Mock
    private JwtUtil jwtUtil;
    @Mock
    private BcryptHashGenerator hashGenerator;
    @InjectMocks
    private LoginService loginService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    void testLogin_Success() {
        String email = "test@example.com";
        String password = "password";
        User user = new User();
        user.setEmail(email);
        user.setPassword("hashed");
        when(userRepository.findByEmail(email)).thenReturn(java.util.Optional.of(user));
        when(hashGenerator.matches(password, "hashed")).thenReturn(true);
        when(jwtUtil.generateToken(user)).thenReturn("jwt-token");

        LoginRequest request = new LoginRequest();
        request.setEmail(email);
        request.setPassword(password);

        AuthResponse response = loginService.login(request);
        assertNotNull(response);
        assertEquals("jwt-token", response.getToken());
    }

    @Test
    void testLogin_Failure_WrongPassword() {
        String email = "test@example.com";
        String password = "wrongpassword";
        User user = new User();
        user.setEmail(email);
        user.setPassword("hashed");
        when(userRepository.findByEmail(email)).thenReturn(java.util.Optional.of(user));
        when(hashGenerator.matches(password, "hashed")).thenReturn(false);

        LoginRequest request = new LoginRequest();
        request.setEmail(email);
        request.setPassword(password);

        Exception exception = assertThrows(RuntimeException.class, () -> loginService.login(request));
        assertTrue(exception.getMessage().toLowerCase().contains("invalid"));
    }

    @Test
    void testLogin_Failure_UserNotFound() {
        String email = "notfound@example.com";
        when(userRepository.findByEmail(email)).thenReturn(java.util.Optional.empty());

        LoginRequest request = new LoginRequest();
        request.setEmail(email);
        request.setPassword("irrelevant");

        Exception exception = assertThrows(RuntimeException.class, () -> loginService.login(request));
        assertTrue(exception.getMessage().toLowerCase().contains("not found"));
    }
}
