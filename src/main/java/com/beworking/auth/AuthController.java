package com.beworking.auth;

import org.springframework.http.HttpStatus; // it contains the HttpStatus enum. It is used to represent HTTP status codes.
import org.springframework.http.ResponseEntity; // it contains the ResponseEntity class. It is used to represent an HTTP response, including status code, headers, and body.
import org.springframework.web.bind.annotation.*; // it contains the annotations for RESTful web services such as @RestController, @RequestMapping, @PostMapping, etc.

import java.util.Map;

@RestController // it indicates that this class is a REST controller. It is a specialized version of the @Controller annotation. It is used to handle HTTP requests and responses in a RESTful manner.
@RequestMapping("/api/auth") // it specifies the base URL for all the endpoints in this controller.
public class AuthController {
    private final AuthService authService;
    private final JwtUtil jwtUtil;

    public AuthController(AuthService authService, JwtUtil jwtUtil) {
        this.authService = authService;
        this.jwtUtil = jwtUtil;
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@RequestBody AuthRequest request) {
        var userOpt = authService.authenticate(request.getEmail(), request.getPassword(), User.Role.USER);
        if (userOpt.isPresent()) {
            String token = jwtUtil.generateToken(request.getEmail(), User.Role.USER.name());
            return ResponseEntity.ok(new AuthResponse("Login successful", token));
        } else {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new AuthResponse("Invalid credentials or role", null));
        }
    }

    @PostMapping("/admin/login")
    public ResponseEntity<AuthResponse> adminLogin(@RequestBody AuthRequest request) {
        var userOpt = authService.authenticate(request.getEmail(), request.getPassword(), User.Role.ADMIN);
        if (userOpt.isPresent()) {
            String token = jwtUtil.generateToken(request.getEmail(), User.Role.ADMIN.name());
            return ResponseEntity.ok(new AuthResponse("Admin login successful", token));
        } else {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new AuthResponse("Invalid admin credentials or role", null));
        }
    }
}
