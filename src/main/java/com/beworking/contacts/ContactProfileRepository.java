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

    @org.springframework.data.jpa.repository.Query(value = """
        SELECT * FROM beworking.contact_profiles c
        WHERE (unaccent(LOWER(c.name)) LIKE unaccent(LOWER(CONCAT('%', :search, '%')))
           OR unaccent(LOWER(c.contact_name)) LIKE unaccent(LOWER(CONCAT('%', :search, '%')))
           OR unaccent(LOWER(c.billing_name)) LIKE unaccent(LOWER(CONCAT('%', :search, '%')))
           OR unaccent(LOWER(c.representative_first_name)) LIKE unaccent(LOWER(CONCAT('%', :search, '%')))
           OR unaccent(LOWER(c.representative_last_name)) LIKE unaccent(LOWER(CONCAT('%', :search, '%')))
           OR LOWER(c.email_primary) LIKE LOWER(CONCAT('%', :search, '%'))
           OR LOWER(c.email_secondary) LIKE LOWER(CONCAT('%', :search, '%'))
           OR LOWER(c.email_tertiary) LIKE LOWER(CONCAT('%', :search, '%'))
           OR LOWER(c.representative_email) LIKE LOWER(CONCAT('%', :search, '%')))
        ORDER BY c.name ASC
    """, nativeQuery = true)
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
