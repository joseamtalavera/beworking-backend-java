package com.beworking.auth;

import io.jsonwebtoken.Jwts; // it is the import of the Jwts class. It is used to create and parse JWT tokens.
import io.jsonwebtoken.SignatureAlgorithm; // it is the import of the SignatureAlgorithm class. It is used to specify the algorithm used to sign the JWT token.
import io.jsonwebtoken.security.Keys; // it is the import of the Keys class. It is used to generate a secret key for signing the JWT token.
import io.jsonwebtoken.Claims; // it is the import of the Claims class. It is used to represent the claims in a JWT token.
import org.springframework.stereotype.Component; // it is the import of the Component annotation. It is used to mark a class as a Spring component. It is used for dependency injection and component scanning.
import org.springframework.beans.factory.annotation.Value;

import java.security.Key;
import java.util.Date;

@Component
public class JwtUtil {
    @Value("${jwt.secret}")
    private String jwtSecret;
    private final long EXPIRATION_TIME = 1000 * 60 * 60; // 1 hour

    private Key getSigningKey() {
        return Keys.hmacShaKeyFor(jwtSecret.getBytes());
    }

    public String generateToken(String email, String role) {
        return Jwts.builder()
                .setSubject(email)
                .claim("role", role)
                .setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis() + EXPIRATION_TIME))
                .signWith(getSigningKey(), SignatureAlgorithm.HS256)
                .compact();
    }

    public Claims parseToken(String token) {
        return Jwts.parserBuilder()
                .setSigningKey(getSigningKey())
                .build()
                .parseClaimsJws(token)
                .getBody();
    }
}
