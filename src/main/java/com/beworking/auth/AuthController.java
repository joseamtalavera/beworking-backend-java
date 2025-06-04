package com.beworking.auth;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final LoginService loginService;
    private final JwtUtil jwtUtil;
    private final RegisterService registerService;

    public AuthController(LoginService loginService, JwtUtil jwtUtil, RegisterService registerService) {
        this.loginService = loginService;
        this.jwtUtil = jwtUtil;
        this.registerService = registerService;
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@RequestBody LoginRequest request) {
        var userOpt = loginService.authenticate(request.getEmail(), request.getPassword());
        if (userOpt.isPresent()) {
            User user = userOpt.get();
            String token = jwtUtil.generateToken(user.getEmail(), user.getRole().name());
            System.out.println("[AuthController] Generated token for user " + user.getEmail() + ": " + token); // Log the token
            return ResponseEntity.ok(new AuthResponse("Login successful", token, user.getRole().name()));
        } else {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new AuthResponse("Invalid credentials", null, null));
        }
    }

    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@RequestBody RegisterRequest request) {
        System.out.println("Recibido register request: name=" + request.getName() + ", email=" + request.getEmail() + ", password=" + request.getPassword());
        boolean created = registerService.registerUser(request.getName(), request.getEmail(), request.getPassword());
        if (created) {
            return ResponseEntity.ok(new AuthResponse("User registered successfully", null, "USER"));
        } else {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(new AuthResponse("User already exists", null, null));
        }
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
