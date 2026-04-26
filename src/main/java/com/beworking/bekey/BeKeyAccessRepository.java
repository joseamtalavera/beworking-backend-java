package com.beworking.bekey;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

public interface BeKeyAccessRepository extends JpaRepository<BeKeyAccess, Long> {

    List<BeKeyAccess> findByContactIdAndRevokedAtIsNull(Long contactId);

    Optional<BeKeyAccess> findBySourceAndSourceRef(BeKeyAccess.Source source, Long sourceRef);

    @Query("""
        SELECT a FROM BeKeyAccess a
        WHERE a.revokedAt IS NULL
          AND a.endsAt IS NOT NULL
          AND a.endsAt < :now
    """)
    List<BeKeyAccess> findExpiredButNotRevoked(OffsetDateTime now);
}
