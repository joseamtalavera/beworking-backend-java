package com.beworking.auth;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.password.PasswordEncoder;
import com.beworking.contacts.ContactProfileRepository;
import java.util.Optional;
import static org.mockito.Mockito.when;
import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
public class AuthControllerTest {
    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private JavaMailSender javaMailSender;

    @MockBean
    private UserRepository userRepository;

    @MockBean
    private PasswordEncoder passwordEncoder;

    @MockBean
    private LoginService loginService;

    @MockBean
    private JwtUtil jwtUtil;

    @MockBean
    private RegisterService registerService;

    @MockBean
    private ContactProfileRepository contactProfileRepository;

   /*  @MockBean
    private RateLimitingFilter rateLimitingFilter; */ 
    // Do not mock the rate limiting filter to avoid interfering with the test
    // In this case, do not run more than 5 requests per minute

    /**
     * Test for login endpoint with invlid credetials.
     * @throws Exception
     */

    
    // Test for login endpoint
    @Test
    void testLogin_Unauthorized() throws Exception {
        // Mock both repository and service to return empty
        when(userRepository.findByEmail("notfound@example.com")).thenReturn(Optional.empty());
        when(loginService.authenticate("notfound@example.com", "wrong")).thenReturn(Optional.empty());
        String json = "{\"email\":\"notfound@example.com\",\"password\":\"wrong\"}";
        mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid credentials")); // Also check error message
    }

    @Test 
    void testLogin_Success() throws Exception {
        User user = new User();
        user.setEmail("user@example.com");
        user.setPassword("hashedPassword");
        user.setRole(User.Role.USER);

        when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("password", "hashedPassword")).thenReturn(true);
        // Mock loginService.authenticate to return the user
        when(loginService.authenticate("user@example.com", "password")).thenReturn(Optional.of(user));
        // Mock jwtUtil.generateToken to return a dummy token
        when(jwtUtil.generateAccessToken("user@example.com", "USER", null)).thenReturn("dummy-jwt-token");
        when(jwtUtil.generateRefreshToken("user@example.com", "USER", null)).thenReturn("dummy-refresh-token");

        String json = "{\"email\":\"user@example.com\",\"password\":\"password\"}";

        mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json))
                .andExpect(status().isOk());
    }

    @Test
    void testAdminLogin_Unauthorized() throws Exception {
        String json =  "{\"email\":\"notfound@example.com\",\"password\":\"wrong\"}";
        mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json))
                .andExpect(status().isUnauthorized());
    }

    @Test 
    void testAdminLogin_Success() throws Exception {
        User admin = new User();
        admin.setEmail("admin@example.com");
        admin.setPassword("hashedAdminPassword");
        admin.setRole(User.Role.ADMIN);

        when(userRepository.findByEmail("admin@example.com")).thenReturn(Optional.of(admin));
        when(passwordEncoder.matches("adminPassword", "hashedAdminPassword")).thenReturn(true);
        // Mock loginService.authenticate to return the admin user
        when(loginService.authenticate("admin@example.com", "adminpass")).thenReturn(Optional.of(admin));
        // Mock jwtUtil.generateToken to return a dummy token
        when(jwtUtil.generateAccessToken("admin@example.com", "ADMIN", null)).thenReturn("dummy-admin-jwt-token");
        when(jwtUtil.generateRefreshToken("admin@example.com", "ADMIN", null)).thenReturn("dummy-admin-refresh-token");

        String json = "{\"email\":\"admin@example.com\",\"password\":\"adminpass\"}";

        mockMvc.perform(post("/api/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content(json))
            .andExpect(status().isOk());
    }

    @Test 
    void testLogin_MissingFields() throws Exception {
        // Missing password field
        String json = "{\"email\":\"\"}";
        mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json))
                .andExpect(status().isBadRequest());
    }


    // Test for register endpoint 

    @Test
    void testRegister_Success() throws Exception {
        String json = "{\"name\":\"New User\",\"email\":\"newuser@example.com\",\"password\":\"newpassword\"}";
        when(registerService.registerUser("New User", "newuser@example.com", "newpassword")).thenReturn(true);

        mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("User registered successfully"))
                .andExpect(jsonPath("$.role").value("USER"));
    }

    @Test 
    void testRegister_Conflict() throws Exception {
        // lets assume that the user already exists
        String json = "{\"name\": \"Existing User\", \"email\": \"existing@example.com\", \"password\": \"existingpassword\"}";
        when(registerService.registerUser("Existing User", "existing@example.com", "existingpassword")).thenReturn(false);

        mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("User already exists"))
                .andExpect(jsonPath("$.role").doesNotExist());
    }

    @Test
    void testRegister_MissingFields() throws Exception {
        // JSON missing the password field
        String json = "{\"name\": \"Missing Password\", \"email\": \"missing@example.com\"}";
        mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json))
                .andExpect(status().isBadRequest());
    }

    // Test for the confirmation endpoint

    @Test 
    void testConfirmation_Success() throws Exception {
        User user = new User();
        user.setConfirmationToken("valid-token");
        user.setConfirmationTokenExpiry(java.time.Instant.now().plusSeconds(3600));
        when(registerService.findByConfirmationToken("valid-token")).thenReturn(Optional.of(user));

        mockMvc.perform(get("/api/auth/confirm")
                .param("token", "valid-token"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "text/html"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Email confirmed successfully!")));
    }

    @Test
    void testConfirmation_InvalidToken() throws Exception {
        // Mock the service to return empty when searching for the token
        when(registerService.findByConfirmationToken("invalid-token")).thenReturn(Optional.empty());
        
        mockMvc.perform(get("/api/auth/confirm")
                .param("token", "invalid-token"))
                .andExpect(status().isBadRequest())
                .andExpect(header().string("Content-Type", "text/html"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Invalid or expired confirmation token")));
    }

    @Test
    void testConfirmEmail_ExpiredToken() throws Exception {
        User user = new User();
        user.setConfirmationToken("expired-token");
        user.setConfirmationTokenExpiry(java.time.Instant.now().minusSeconds(3600));
        when(registerService.findByConfirmationToken("expired-token")).thenReturn(Optional.of(user));

        mockMvc.perform(get("/api/auth/confirm")
                .param("token", "expired-token"))
                .andExpect(status().isBadRequest())
                .andExpect(header().string("Content-Type", "text/html"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Confirmation token expired")));
    }

    // Test for forgot password endpoint

    @Test
    void testForgotPassword_Success() throws Exception {
        when(registerService.sendPasswordResetEmail("user@example.com")).thenReturn(true);

        String json = "{\"email\": \"user@example.com\"}";
        mockMvc.perform(post("/api/auth/forgot-password")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("If the email exists")));
    }

    @Test 
    void testForgotPassword_MissingEmail() throws Exception {
        // JSON missing the email field
        String json = "{}";

        mockMvc.perform(post("/api/auth/forgot-password")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Email is required")));
    }

    // Test for reset password endpoint
    @Test
    void testResetPassword_Sucess() throws Exception {
        when(registerService.resetPassword("token", "newpassword")).thenReturn(true);

        String json = "{\"token\": \"token\", \"newPassword\": \"newpassword\"}";

        mockMvc.perform(post("/api/auth/reset-password")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Password reset successfully")));
    }

    @Test
    void testResetPassword_MissingFields() throws Exception {
        // JSON missing fields
        String json = "{\"token\": \"\", \"newPassword\": \"\"}";

        mockMvc.perform(post("/api/auth/reset-password")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Token and new password are required")));
    }

    @Test
    void testResetPassword_InvalidToken() throws Exception {

        when(registerService.resetPassword("invalid-token", "newpass")).thenReturn(false);

        String json = "{\"token\":\"invalid-token\",\"newPassword\":\"newpass\"}";
        
        mockMvc.perform(post("/api/auth/reset-password")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Invalid or expired token")));
    }
}

