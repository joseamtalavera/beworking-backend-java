package com.beworking.auth;

import org.springframework.data.jpa.repository.JpaRepository; // it contains the JpaRepository interface. It is a part of Spring Data JPA and provides methods for CRUD operations and pagination.
import java.util.Optional; // it is a container object which may or may not contain a non-null value. It is used to avoid null references and NullPointerExceptions.


public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByEmail(String email);
} 
