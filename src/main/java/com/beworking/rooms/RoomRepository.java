package com.beworking.rooms;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RoomRepository extends JpaRepository<Room, Long> {

    @EntityGraph(attributePaths = {"images", "amenities"})
    List<Room> findAll();

    Optional<Room> findByCodeIgnoreCase(String code);

    boolean existsByCodeIgnoreCase(String code);
}
