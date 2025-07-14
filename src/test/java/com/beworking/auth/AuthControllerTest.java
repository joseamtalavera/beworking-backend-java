package com.beworking.auth;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.password.PasswordEncoder;
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
        when(jwtUtil.generateToken("user@example.com", "USER")).thenReturn("dummy-jwt-token");

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
        when(jwtUtil.generateToken("admin@example.com", "ADMIN")).thenReturn("dummy-admin-jwt-token");

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
}

