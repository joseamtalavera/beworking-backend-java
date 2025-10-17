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

    Optional<ContactProfile> findFirstByEmailPrimaryIgnoreCaseOrEmailSecondaryIgnoreCaseOrEmailTertiaryIgnoreCaseOrRepresentativeEmailIgnoreCase(
        String emailPrimary,
        String emailSecondary,
        String emailTertiary,
        String representativeEmail
    );
}
