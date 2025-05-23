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
        var userOpt = loginService.authenticate(request.getEmail(), request.getPassword(), User.Role.USER);
        if (userOpt.isPresent()) {
            String token = jwtUtil.generateToken(request.getEmail(), User.Role.USER.name());
            return ResponseEntity.ok(new AuthResponse("Login successful", token));
        } else {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new AuthResponse("Invalid credentials or role", null));
        }
    }

    @PostMapping("/admin/login")
    public ResponseEntity<AuthResponse> adminLogin(@RequestBody LoginRequest request) {
        var userOpt = loginService.authenticate(request.getEmail(), request.getPassword(), User.Role.ADMIN);
        if (userOpt.isPresent()) {
            String token = jwtUtil.generateToken(request.getEmail(), User.Role.ADMIN.name());
            return ResponseEntity.ok(new AuthResponse("Admin login successful", token));
        } else {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new AuthResponse("Invalid admin credentials or role", null));
        }
    }

    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@RequestBody RegisterRequest request) {
        System.out.println("Recivido register request: name=" + request.getName() + ", email=" + request.getEmail() + ", password=" + request.getPassword());
        boolean created = registerService.registerUser(request.getName(), request.getEmail(), request.getPassword());
        if (created) {
            return ResponseEntity.ok(new AuthResponse("User registered successfully", null));
        } else {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(new AuthResponse("User already exists", null));
        }
    }

}
