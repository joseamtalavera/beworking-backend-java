package com.beworking.auth;

import io.jsonwebtoken.Jwts; // it is the import of the Jwts class. It is used to create and parse JWT tokens.
import io.jsonwebtoken.SignatureAlgorithm; // it is the import of the SignatureAlgorithm class. It is used to specify the algorithm used to sign the JWT token.
import io.jsonwebtoken.security.Keys; // it is the import of the Keys class. It is used to generate a secret key for signing the JWT token.
import io.jsonwebtoken.Claims; // it is the import of the Claims class. It is used to represent the claims in a JWT token.
import org.springframework.stereotype.Component; // it is the import of the Component annotation. It is used to mark a class as a Spring component. It is used for dependency injection and component scanning.
import org.springframework.beans.factory.annotation.Value;

import java.security.Key;
import java.util.Date;

// ---
// JWT Secret Management Steps (for reference, remove before production)
// 1. The secret is injected from application.properties via @Value.
// 2. Never hardcode secrets in code. Always use environment variables for production.
// 3. The secret is used to sign and verify JWT tokens.
// ---
@Component
public class JwtUtil {
    @Value("${jwt.secret}")
    private String jwtSecret; // Step 1: Secret injected here
    private final long EXPIRATION_TIME = 1000 * 60 * 60; // 1 hour

    private Key getSigningKey() {
        return Keys.hmacShaKeyFor(jwtSecret.getBytes()); // Step 3: Used for signing/verifying
    }

    // ---
    // JWT Token Generation Process:
    // 1. generateToken() is called with user email and role.
    // 2. Sets subject (email), adds role claim, sets issued/expiration dates.
    // 3. Signs the token with the secret key and HS256 algorithm.
    // 4. Returns the compact JWT string to the client.
    // ---
    public String generateToken(String email, String role) {
        return Jwts.builder()
                .setSubject(email)
                .claim("role", role)
                .setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis() + EXPIRATION_TIME))
                .signWith(getSigningKey(), SignatureAlgorithm.HS256)
                .compact();
    }

    // ---
    // JWT Token Verification Process:
    // 1. parseToken() is called with the JWT string from the client.
    // 2. Uses the secret key to verify the signature and parse claims.
    // 3. Returns the claims if valid, or throws if invalid/expired.
    // ---
    public Claims parseToken(String token) {
        return Jwts.parserBuilder()
                .setSigningKey(getSigningKey())
                .build()
                .parseClaimsJws(token)
                .getBody();
    }
}
// ---
