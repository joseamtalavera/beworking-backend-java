package com.beworking.bekey;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.OffsetDateTime;
import java.util.List;

public interface BeKeyShareRepository extends JpaRepository<BeKeyShare, Long> {

    /** Active (non-revoked) shares created by a member, newest first. */
    List<BeKeyShare> findBySharerContactIdAndRevokedAtIsNullOrderByCreatedAtDesc(Long sharerContactId);

    /** Count of a member's active shares — used to enforce the per-member cap. */
    long countBySharerContactIdAndRevokedAtIsNull(Long sharerContactId);

    /** All a member's shares (active + past), newest first. */
    List<BeKeyShare> findBySharerContactIdOrderByCreatedAtDesc(Long sharerContactId);

    /** Shares whose window has ended but that haven't been revoked yet — for the expiry sweep. */
    List<BeKeyShare> findByRevokedAtIsNullAndEndsAtBefore(OffsetDateTime cutoff);
}
