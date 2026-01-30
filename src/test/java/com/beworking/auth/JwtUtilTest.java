package com.beworking.auth;

import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

class JwtUtilTest {

    private JwtUtil jwtUtil;

    // Use a 256 bit base64 encoded secret key for HS256
    private static final String secret = Base64.getEncoder().encodeToString("my-very-secret-key-1234567890abcd".getBytes()); 

    @BeforeEach
    void setUp() {
        jwtUtil = new JwtUtil();
        // Inject the secret key into the private field
        ReflectionTestUtils.setField(jwtUtil, "jwtSecret", secret);
    }

    @Test
    void testGenerateAndParseToken_ValidToken_Success() {
        String email = "user@example.com";
        String role = "USER";

        String token = jwtUtil.generateAccessToken(email, role, null);
        assertNotNull(token);

        Claims claims = jwtUtil.parseToken(token);
        assertEquals(email, claims.getSubject());
        assertEquals(role, claims.get("role"));
        assertEquals("access", claims.get("tokenType"));
        assertNotNull(claims.getIssuedAt());
        assertNotNull(claims.getExpiration());
    }

    @Test
    void parseToken_InvalidToken_ThrowsException() {
        String invalidToken = "invalid.token.value";
        assertThrows(Exception.class, () -> jwtUtil.parseToken(invalidToken));
    }

    @Test
    void parseToken_ExpiredToken_ThrowsException() {

    }

}