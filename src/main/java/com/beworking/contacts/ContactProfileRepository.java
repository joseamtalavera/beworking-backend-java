package com.beworking.contacts;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

@Repository

public interface ContactProfileRepository extends JpaRepository<ContactProfile, Long>, JpaSpecificationExecutor<ContactProfile> {

    List<ContactProfile> findTop50ByOrderByNameAsc();

    List<ContactProfile> findTop50ByNameContainingIgnoreCaseOrContactNameContainingIgnoreCaseOrEmailPrimaryContainingIgnoreCase(
        String name,
        String contactName,
        String email
    );

    @org.springframework.data.jpa.repository.Query("""
        SELECT c FROM ContactProfile c
        WHERE (LOWER(c.name) LIKE LOWER(CONCAT('%', :search, '%'))
           OR LOWER(c.contactName) LIKE LOWER(CONCAT('%', :search, '%'))
           OR LOWER(c.billingName) LIKE LOWER(CONCAT('%', :search, '%'))
           OR LOWER(c.representativeFirstName) LIKE LOWER(CONCAT('%', :search, '%'))
           OR LOWER(c.representativeLastName) LIKE LOWER(CONCAT('%', :search, '%'))
           OR LOWER(c.emailPrimary) LIKE LOWER(CONCAT('%', :search, '%'))
           OR LOWER(c.emailSecondary) LIKE LOWER(CONCAT('%', :search, '%'))
           OR LOWER(c.emailTertiary) LIKE LOWER(CONCAT('%', :search, '%'))
           OR LOWER(c.representativeEmail) LIKE LOWER(CONCAT('%', :search, '%')))
        ORDER BY c.name ASC
    """)
    List<ContactProfile> searchContacts(@org.springframework.data.repository.query.Param("search") String search);

    Optional<ContactProfile> findFirstByEmailPrimaryIgnoreCaseOrEmailSecondaryIgnoreCaseOrEmailTertiaryIgnoreCaseOrRepresentativeEmailIgnoreCase(
        String emailPrimary,
        String emailSecondary,
        String emailTertiary,
        String representativeEmail
    );

    @org.springframework.data.jpa.repository.Query("SELECT MAX(c.id) FROM ContactProfile c")
    Optional<Long> findMaxId();
}
