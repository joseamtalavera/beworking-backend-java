package com.beworking.auth;


import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.junit.jupiter.api.BeforeEach;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.Mockito.when;
import java.util.Optional;

@SpringBootTest
@AutoConfigureMockMvc(addFilters = true)
@Import(TestMailConfig.class)
class RateLimitingFilterTest {
    @Autowired
    private WebApplicationContext context;
    private MockMvc mockMvc;

    @MockBean
    private LoginService loginService;

    @MockBean
    private JwtUtil jwtUtil;


    @MockBean
    private UserRepository userRepository;

    // Helper method to set the remote address (IP) for the request of a mock HTTP request in the test.
    private RequestPostProcessor remoteAddr(final String remoteAddr) {
        return request -> {
            request.setRemoteAddr(remoteAddr);
            return request;
        };
    }

    @BeforeEach
    void setup() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).addFilters(context.getBean(RateLimitingFilter.class)).build();
    }

    @Test
    void testLimitingWorks() throws Exception {
        // Mock loginService to return a valid user
        User user = new User("a@b.com", "hashedPassword", User.Role.USER);
        when(userRepository.findByEmail("a@b.com")).thenReturn(Optional.of(user));
        when(loginService.authenticate("a@b.com", "password")).thenReturn(Optional.of(user));
        when(jwtUtil.generateToken("a@b.com", "USER")).thenReturn("dummyToken");

        String endpoint = "/api/auth/login";
        for (int i = 1; i <= 5; i++) {
            mockMvc.perform(post(endpoint)
                    .with(remoteAddr("1.2.3.4"))
                    .contentType("application/json")
                    .content("{\"email\":\"a@b.com\", \"password\":\"password\"}"))
                    .andExpect(status().isOk());
        }
        // The 6th request should be rate-limited
        mockMvc.perform(post(endpoint)
                .with(remoteAddr("1.2.3.4"))
                .content("application/json")
                .content("{\"email\":\"a@b.com\",\"password\":\"pass\"}"))
                .andExpect(status().isTooManyRequests());         
    }

}