package com.beworking.bekey;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface BeKeyShareRepository extends JpaRepository<BeKeyShare, Long> {

    /** Active (non-revoked) shares created by a member, newest first. */
    List<BeKeyShare> findBySharerContactIdAndRevokedAtIsNullOrderByCreatedAtDesc(Long sharerContactId);

    /** Count of a member's active shares — used to enforce the per-member cap. */
    long countBySharerContactIdAndRevokedAtIsNull(Long sharerContactId);

    /** All a member's shares (active + past), newest first. */
    List<BeKeyShare> findBySharerContactIdOrderByCreatedAtDesc(Long sharerContactId);
}
