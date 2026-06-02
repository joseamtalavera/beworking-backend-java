package com.beworking.bekey;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface BeKeyDeviceRepository extends JpaRepository<BeKeyDevice, Long> {
    Optional<BeKeyDevice> findByAkilesGadgetId(String akilesGadgetId);
    List<BeKeyDevice> findByAkilesSiteId(String akilesSiteId);
    
}