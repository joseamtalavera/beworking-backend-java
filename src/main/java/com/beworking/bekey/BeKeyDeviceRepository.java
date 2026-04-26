package com.beworking.bekey;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface BeKeyDeviceRepository extends JpaRepository<BeKeyDevice, Long> {
    Optional<BeKeyDevice> findByAkilesGadgetId(String akilesGadgetId);
    
}