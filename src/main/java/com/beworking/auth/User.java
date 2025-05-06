package com.beworking.auth; // package declaration

import jakarta.persistence.*; // it contanis the annotations for JPA such as @Entity, @Table, @Id, @GeneratedValue, etc.
import jakarta.persistence.GenerationType; // it contains the GenerationType enum. A enum is a special Java type used to define collections of constants.

@Entity // it specifies that the class is an entity and is mapped to a database table.
@Table(name = "users")// it specifies the name of the database table to be used for mapping.
public class User {
    @Id // it specifies the primary key of the entity.
    @GeneratedValue(strategy = GenerationType.IDENTITY) // it specifies the generation strategy for the primary key. IDENTITY means that the database will generate the primary key value.
    private Long id; // it is the primary key of the entity.

    @Column(nullable = false, unique = true)
    private String email;

    @Column(nullable = false)
    private String password;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role;

    public enum Role {
        ADMIN, USER
    }

    // Constructors
    public User() {}

    public User(String email, String password, Role role) {
        this.email = email;
        this.password = password;
        this.role = role;
    }

    // Getters and setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }

    public Role getRole() { return role; }
    public void setRole(Role role) { this.role = role; }
}
