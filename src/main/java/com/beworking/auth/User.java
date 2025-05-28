package com.beworking.auth;

import jakarta.persistence.*;
import jakarta.persistence.GenerationType;

/**
 * User entity representing application users for multi-tenant, role-based authentication.
 */
@Entity
@Table(name = "users", schema = "beworking")
public class User {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(nullable = false)
    private String password;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role;

    @Column(name = "tenant_id", nullable = true)
    private Long tenantId;

    public enum Role {
        ADMIN, USER;
        // For future extensibility, you can add more roles here
    }

    // Builder pattern for easier instantiation
    public static class Builder {
        private String email;
        private String password;
        private Role role;
        private Long tenantId;

        public Builder email(String email) {
            this.email = email;
            return this;
        }
        public Builder password(String password) {
            this.password = password;
            return this;
        }
        public Builder role(Role role) {
            this.role = role;
            return this;
        }
        public Builder tenantId(Long tenantId) {
            this.tenantId = tenantId;
            return this;
        }
        public User build() {
            User user = new User(email, password, role);
            user.setTenantId(tenantId);
            return user;
        }
    }

    /**
     * Returns true if the user has ADMIN role.
     */
    public boolean isAdmin() {
        return this.role == Role.ADMIN;
    }
    /**
     * Returns true if the user has USER role.
     */
    public boolean isUser() {
        return this.role == Role.USER;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        User user = (User) o;
        return java.util.Objects.equals(id, user.id) &&
                java.util.Objects.equals(email, user.email);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(id, email);
    }

    @Override
    public String toString() {
        return "User{" +
                "id=" + id +
                ", email='" + email + '\'' +
                ", role=" + role +
                ", tenantId=" + tenantId +
                '}';
    }

    public User() {}

    public User(String email, String password, Role role) {
        this.email = email;
        this.password = password;
        this.role = role;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }

    public Role getRole() { return role; }
    public void setRole(Role role) { this.role = role; }

    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }
}
