package com.beworking.bekey;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface BeKeyMemberIdentityRepository extends JpaRepository<BeKeyMemberIdentity, Long> {
    Optional<BeKeyMemberIdentity> findByAkilesMemberId(String akilesMemberId);
    
}