package com.beworking.auth;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

public class BcryptHashGenerator {
    public static void main(String[] args) {
        String password = "@Rakna6164";
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
        String hash = encoder.encode(password);
        System.out.println("BCrypt hash: " + hash);
    }
}
