package com.beworking.auth;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

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

}
