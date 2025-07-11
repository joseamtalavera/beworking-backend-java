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

    /**
     * Test for login endpoint with invlid credetials.
     * @throws Exception
     */

    @Test
    void testLogin_Unauthorized() throws Exception {
        String json = "{\"email\":\"notfound@example.com\",\"password\":\"wrong\"}";
        mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json))
                .andExpect(status().isUnauthorized());
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
}

