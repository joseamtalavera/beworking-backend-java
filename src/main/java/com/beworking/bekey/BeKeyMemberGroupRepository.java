package com.beworking.bekey;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface BeKeyMemberGroupRepository extends JpaRepository<BeKeyMemberGroup, Long> {
    Optional<BeKeyMemberGroup> findByAkilesGroupId(String akilesGroupId);
    Optional<BeKeyMemberGroup> findByScope(String scope);
}
