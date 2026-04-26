package com.beworking.bekey;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import java.util.Optional;

public interface BeKeyEventRepository extends JpaRepository<BeKeyEvent, Long> {
    Optional<BeKeyEvent> findByAkilesEventId(String akilesEventId);
    Page<BeKeyEvent> findByContactIdOrderByOccurredAtDesc(Long contactId, Pageable pageable);
    Page<BeKeyEvent> findByDeviceIdOrderByOccurredAtDesc(Long deviceId, Pageable pageable);
}
