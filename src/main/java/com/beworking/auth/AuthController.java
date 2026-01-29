package com.beworking.auth;

import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")

public class AuthController {
    private final LoginService loginService;
    private final JwtUtil jwtUtil;
    private final RegisterService registerService;
    private final UserRepository userRepository;
    @Value("${app.security.cookie-secure:true}")
    private boolean cookieSecure;
    private static final String ACCESS_COOKIE = "beworking_access";
    private static final String REFRESH_COOKIE = "beworking_refresh";

    public AuthController(LoginService loginService, JwtUtil jwtUtil, RegisterService registerService, UserRepository userRepository) {
        this.loginService = loginService;
        this.jwtUtil = jwtUtil;
        this.registerService = registerService;
        this.userRepository = userRepository;
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        var userOpt = loginService.authenticate(request.getEmail(), request.getPassword());
        if (userOpt.isPresent()) {
            User user = userOpt.get();
            if (!user.isEmailConfirmed()) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(new AuthResponse("Please confirm your email before logging in", null, null));
            }
            String access = jwtUtil.generateAccessToken(user.getEmail(), user.getRole().name(), user.getTenantId());
            String refresh = jwtUtil.generateRefreshToken(user.getEmail(), user.getRole().name(), user.getTenantId());

            ResponseCookie accessCookie = ResponseCookie.from(ACCESS_COOKIE, access)
                    .httpOnly(true)
                    .secure(cookieSecure)
                    .sameSite("Lax")
                    .path("/")
                    .maxAge(Duration.ofMinutes(15))
                    .build();

            ResponseCookie refreshCookie = ResponseCookie.from(REFRESH_COOKIE, refresh)
                    .httpOnly(true)
                    .secure(cookieSecure)
                    .sameSite("Lax")
                    .path("/api/auth/refresh")
                    .maxAge(Duration.ofDays(7))
                    .build();

            return ResponseEntity.ok()
                    .header("Set-Cookie", accessCookie.toString())
                    .header("Set-Cookie", refreshCookie.toString())
                    .body(new AuthResponse("Login successful", null, user.getRole().name()));
        } else {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new AuthResponse("Invalid credentials", null, null));
        }
    }

    @PostMapping("/refresh")
    public ResponseEntity<AuthResponse> refresh(@CookieValue(name = REFRESH_COOKIE, required = false) String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new AuthResponse("Missing refresh token", null, null));
        }
        try {
            var claims = jwtUtil.parseToken(refreshToken);
            if (!"refresh".equals(claims.get("tokenType", String.class))) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(new AuthResponse("Invalid token type", null, null));
            }
            String email = claims.getSubject();
            String role = claims.get("role", String.class);
            Long tenantId = claims.get("tenantId", Long.class);

            String newAccess = jwtUtil.generateAccessToken(email, role, tenantId);
            String newRefresh = jwtUtil.generateRefreshToken(email, role, tenantId);
            ResponseCookie accessCookie = ResponseCookie.from(ACCESS_COOKIE, newAccess)
                    .httpOnly(true)
                    .secure(cookieSecure)
                    .sameSite("Lax")
                    .path("/")
                    .maxAge(Duration.ofMinutes(15))
                    .build();

            ResponseCookie refreshCookie = ResponseCookie.from(REFRESH_COOKIE, newRefresh)
                    .httpOnly(true)
                    .secure(cookieSecure)
                    .sameSite("Lax")
                    .path("/api/auth/refresh")
                    .maxAge(Duration.ofDays(7))
                    .build();

            return ResponseEntity.ok()
                    .header("Set-Cookie", accessCookie.toString())
                    .header("Set-Cookie", refreshCookie.toString())
                    .body(new AuthResponse("Token refreshed", null, role));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new AuthResponse("Invalid or expired refresh token", null, null));
        }
    }

    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request) {
        boolean created = registerService.registerUser(request.getName(), request.getEmail(), request.getPassword());
        if (created) {
            return ResponseEntity.ok(new AuthResponse("User registered successfully", null, "USER"));
        } else {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(new AuthResponse("User already exists", null, null));
        }
    }

    @GetMapping("/me")
    public ResponseEntity<?> currentUser(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("message", "Unauthorized"));
        }

        String email = authentication.getName();
        return userRepository
                .findByEmail(email)
                .<ResponseEntity<?>>map(user -> {
                    Map<String, Object> userData = new java.util.HashMap<>();
                    userData.put("email", user.getEmail());
                    userData.put("name", user.getName());
                    userData.put("phone", user.getPhone());
                    userData.put("role", user.getRole().name());
                    userData.put("tenantId", user.getTenantId()); // This can be null
                    userData.put("avatar", user.getAvatar()); // This can be null
                    userData.put("address", Map.of(
                        "line1", user.getAddressLine1() != null ? user.getAddressLine1() : "",
                        "city", user.getAddressCity() != null ? user.getAddressCity() : "",
                        "country", user.getAddressCountry() != null ? user.getAddressCountry() : "",
                        "postal", user.getAddressPostal() != null ? user.getAddressPostal() : ""
                    ));
                    userData.put("billing", Map.of(
                        "brand", user.getBillingBrand() != null ? user.getBillingBrand() : "",
                        "last4", user.getBillingLast4() != null ? user.getBillingLast4() : "",
                        "expMonth", user.getBillingExpMonth() != null ? user.getBillingExpMonth() : 0,
                        "expYear", user.getBillingExpYear() != null ? user.getBillingExpYear() : 0,
                        "stripeCustomerId", user.getStripeCustomerId() != null ? user.getStripeCustomerId() : ""
                    ));
                    return ResponseEntity.ok(userData);
                })
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("message", "User not found")));
    }

    @PutMapping("/me/avatar")
    public ResponseEntity<?> updateAvatar(Authentication authentication, @RequestBody Map<String, String> request) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("message", "Unauthorized"));
        }

        String email = authentication.getName();
        String avatarUrl = request.get("avatar");
        
        return userRepository
                .findByEmail(email)
                .<ResponseEntity<?>>map(user -> {
                    user.setAvatar(avatarUrl);
                    userRepository.save(user);
                    return ResponseEntity.ok(Map.of("message", "Avatar updated successfully", "avatar", avatarUrl));
                })
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("message", "User not found")));
    }

    @PutMapping("/me")
    public ResponseEntity<?> updateProfile(Authentication authentication, @RequestBody Map<String, Object> request) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("message", "Unauthorized"));
        }

        String email = authentication.getName();
        
        return userRepository
                .findByEmail(email)
                .<ResponseEntity<?>>map(user -> {
                    // Update user fields if provided
                    if (request.containsKey("name")) {
                        user.setName((String) request.get("name"));
                    }
                    if (request.containsKey("email")) {
                        user.setEmail((String) request.get("email"));
                    }
                    if (request.containsKey("phone")) {
                        user.setPhone((String) request.get("phone"));
                    }
                    if (request.containsKey("avatar")) {
                        user.setAvatar((String) request.get("avatar"));
                    }
                    
                    // Update billing if provided
                    if (request.containsKey("billing")) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> billing = (Map<String, Object>) request.get("billing");
                        if (billing.containsKey("brand")) {
                            user.setBillingBrand((String) billing.get("brand"));
                        }
                        if (billing.containsKey("last4")) {
                            user.setBillingLast4((String) billing.get("last4"));
                        }
                        if (billing.containsKey("expMonth")) {
                            user.setBillingExpMonth((Integer) billing.get("expMonth"));
                        }
                        if (billing.containsKey("expYear")) {
                            user.setBillingExpYear((Integer) billing.get("expYear"));
                        }
                        if (billing.containsKey("stripeCustomerId")) {
                            user.setStripeCustomerId((String) billing.get("stripeCustomerId"));
                        }
                    }
                    
                    // Update address if provided
                    if (request.containsKey("address")) {
                        @SuppressWarnings("unchecked")
                        Map<String, String> address = (Map<String, String>) request.get("address");
                        if (address.containsKey("line1")) {
                            user.setAddressLine1(address.get("line1"));
                        }
                        if (address.containsKey("city")) {
                            user.setAddressCity(address.get("city"));
                        }
                        if (address.containsKey("country")) {
                            user.setAddressCountry(address.get("country"));
                        }
                        if (address.containsKey("postal")) {
                            user.setAddressPostal(address.get("postal"));
                        }
                    }
                    
                    userRepository.save(user);
                    
                    // Return updated user data
                    Map<String, Object> userData = new java.util.HashMap<>();
                    userData.put("email", user.getEmail());
                    userData.put("name", user.getName());
                    userData.put("phone", user.getPhone());
                    userData.put("role", user.getRole().name());
                    userData.put("tenantId", user.getTenantId());
                    userData.put("avatar", user.getAvatar());
                    userData.put("address", Map.of(
                        "line1", user.getAddressLine1() != null ? user.getAddressLine1() : "",
                        "city", user.getAddressCity() != null ? user.getAddressCity() : "",
                        "country", user.getAddressCountry() != null ? user.getAddressCountry() : "",
                        "postal", user.getAddressPostal() != null ? user.getAddressPostal() : ""
                    ));
                    userData.put("billing", Map.of(
                        "brand", user.getBillingBrand() != null ? user.getBillingBrand() : "",
                        "last4", user.getBillingLast4() != null ? user.getBillingLast4() : "",
                        "expMonth", user.getBillingExpMonth() != null ? user.getBillingExpMonth() : 0,
                        "expYear", user.getBillingExpYear() != null ? user.getBillingExpYear() : 0,
                        "stripeCustomerId", user.getStripeCustomerId() != null ? user.getStripeCustomerId() : ""
                    ));
                    
                    return ResponseEntity.ok(Map.of("message", "Profile updated successfully", "user", userData));
                })
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("message", "User not found")));
    }

    @GetMapping("/confirm")
    public ResponseEntity<String> confirmationEmail(@RequestParam("token") String token) {
        var userOpt = registerService.findByConfirmationToken(token);
        String loginUrl = "http://localhost:3020/main/login";
        String buttonStyle = "background:#388e3c;color:#fff;border:none;border-radius:4px;padding:0.75em 2em;font-size:1.1em;cursor:pointer;margin-top:2em;box-shadow:0 2px 8px #0001;transition:background 0.2s;";
        String cardStyle = "max-width:420px;margin:3em auto;background:#fff;border-radius:12px;box-shadow:0 2px 16px #0002;padding:2.5em 2em;text-align:center;font-family:sans-serif;";
        String bodyStyle = "background:#f5f7fa;min-height:100vh;";
        if (userOpt.isEmpty()) {
            String errorHtml = String.format("""
                <html><body style='%s'>
                <div style='%s'>
                <h2 style='color:#d32f2f;'>Invalid or expired confirmation token</h2>
                <p style='color:#555;'>Please check your email for the correct link or register again.</p>
                <a href='%s'><button style='%s'>Go to Login</button></a>
                </div></body></html>
                """, bodyStyle, cardStyle, loginUrl, buttonStyle);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .header("Content-Type", "text/html")
                .body(errorHtml);
        }
        var user = userOpt.get();
        if (user.getConfirmationTokenExpiry() == null || user.getConfirmationTokenExpiry().isBefore(java.time.Instant.now())) {
            String expiredHtml = String.format("""
                <html><body style='%s'>
                <div style='%s'>
                <h2 style='color:#d32f2f;'>Confirmation token expired</h2>
                <p style='color:#555;'>Your confirmation link has expired. Please register again.</p>
                <a href='%s'><button style='%s'>Go to Login</button></a>
                </div></body></html>
                """, bodyStyle, cardStyle, loginUrl, buttonStyle);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .header("Content-Type", "text/html")
                .body(expiredHtml);
        }
        user.setEmailConfirmed(true);
        user.setConfirmationToken(null);
        user.setConfirmationTokenExpiry(null);
        registerService.saveUser(user);
        String successHtml = String.format("""
            <html><body style='%s'>
            <div style='%s'>
            <h2 style='color:#388e3c;'>Email confirmed successfully!</h2>
            <p style='color:#555;'>Your account is now active. You can log in below.</p>
            <a href='%s'><button style='%s'>Go to Login</button></a>
            </div></body></html>
            """, bodyStyle, cardStyle, loginUrl, buttonStyle);
        return ResponseEntity.ok()
            .header("Content-Type", "text/html")
            .body(successHtml);
    }

    @PostMapping("/forgot-password")
    public ResponseEntity<String> forgotPassword(@RequestBody Map<String, String> payload) {
        String email = payload.get("email");
        if (email == null || email.isBlank()) {
            return ResponseEntity.badRequest().body("Email is required");
        }
        boolean sent = registerService.sendPasswordResetEmail(email);
        // Always return this message
        return ResponseEntity.ok("If the email exists, a reset link will be sent.");
    }

    @PostMapping("/reset-password")
    public ResponseEntity<String> resetPassword(@RequestBody Map<String, String> payload) {
        String token = payload.get("token");
        String newPassword = payload.get("newPassword");
        if (token == null || token.isBlank() || newPassword == null || newPassword.isBlank()) {
            return ResponseEntity.badRequest().body("Token and new password are required");
        }
        boolean reset = registerService.resetPassword(token, newPassword);
        if (reset) {
            return ResponseEntity.ok("Password reset successfully.");
        } else { 
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Invalid or expired token");
        }
    }
}
