package com.beworking.rooms.catalog;

import com.beworking.rooms.Room;
import com.beworking.rooms.RoomAmenity;
import com.beworking.rooms.RoomImage;
import com.beworking.rooms.RoomRepository;
import jakarta.transaction.Transactional;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * Handles all business logic for the admin space catalog. Responsible for
 * translating between the {@link Room} entity graph and the public DTOs
 * consumed by the dashboard.
 */
@Service
public class SpaceCatalogService {

    private final RoomRepository roomRepository;

    public SpaceCatalogService(RoomRepository roomRepository) {
        this.roomRepository = roomRepository;
    }

    /**
     * Returns every room mapped to the catalog DTO.
     */
    public List<SpaceCatalogDto> listSpaces() {
        return roomRepository.findAll().stream().map(this::toDto).toList();
    }

    /**
     * Finds a space by database id.
     */
    public Optional<SpaceCatalogDto> findById(Long id) {
        return roomRepository.findById(id).map(this::toDto);
    }

    /**
     * Finds a space by its unique code (case-insensitive).
     */
    public Optional<SpaceCatalogDto> findByCode(String code) {
        return roomRepository.findByCodeIgnoreCase(code).map(this::toDto);
    }

    /**
     * Creates or updates a room using the provided payload. Child collections
     * (images/amenities) are replaced to mirror the DTO exactly.
     */
    @Transactional
    public SpaceCatalogDto save(SpaceCatalogDto payload) {
        Room room = payload.id() != null
            ? roomRepository.findById(payload.id()).orElse(new Room())
            : new Room();

        apply(room, payload);
        Room saved = roomRepository.save(room);
        return toDto(saved);
    }

    /**
     * Removes a room by id.
     */
    public void delete(Long id) {
        roomRepository.deleteById(id);
    }

    /**
     * Copies DTO values into a managed {@link Room} entity.
     */
    private void apply(Room room, SpaceCatalogDto dto) {
        room.setCode(dto.code());
        room.setCentroCode(dto.centroCode());
        room.setName(dto.displayName());
        room.setSubtitle(dto.subtitle());
        room.setDescription(dto.description());
        room.setAddress(dto.address());
        room.setCity(dto.city());
        room.setPostalCode(dto.postalCode());
        room.setCountry(dto.country());
        room.setRegion(dto.region());
        room.setType(dto.type());
        room.setStatus(dto.status());
        room.setCreationDate(dto.creationDate());
        room.setSizeSqm(dto.sizeSqm());
        room.setCapacity(dto.capacity());
        room.setPriceFrom(dto.priceFrom());
        room.setPriceUnit(dto.priceUnit());
        room.setPriceHourMin(dto.priceHourMin());
        room.setPriceHourMed(dto.priceHourMed());
        room.setPriceHourMax(dto.priceHourMax());
        room.setPriceDay(dto.priceDay());
        room.setPriceMonth(dto.priceMonth());
        room.setWifiCredentials(dto.wifiCredentials());
        room.setSortOrder(dto.sortOrder());
        room.setRatingAverage(dto.ratingAverage());
        room.setRatingCount(dto.ratingCount());
        room.setInstantBooking(Boolean.TRUE.equals(dto.instantBooking()));
        room.setTags(dto.tags() == null ? null : String.join(",", dto.tags()));
        room.setHeroImage(dto.heroImage());

        room.getImages().clear();
        if (dto.images() != null) {
            dto.images().forEach(imageDto -> {
                RoomImage image = new RoomImage();
                image.setRoom(room);
                image.setUrl(imageDto.url());
                image.setCaption(imageDto.caption());
                image.setFeatured(Boolean.TRUE.equals(imageDto.featured()));
                image.setSortOrder(imageDto.sortOrder());
                room.getImages().add(image);
            });
        }

        room.getAmenities().clear();
        if (dto.amenities() != null) {
            dto.amenities().forEach(code -> {
                RoomAmenity amenity = new RoomAmenity();
                amenity.setRoom(room);
                amenity.setAmenityCode(code);
                room.getAmenities().add(amenity);
            });
        }
    }

    /**
     * Builds the public DTO from the entity graph, splitting tags and mapping children.
     */
    private SpaceCatalogDto toDto(Room room) {
        List<String> tags = room.getTags() == null || room.getTags().isBlank()
            ? List.of()
            : List.of(room.getTags().split("\\s*,\\s*"));

        List<SpaceCatalogImageDto> images = room.getImages().stream()
            .map(img -> new SpaceCatalogImageDto(
                img.getId(),
                img.getUrl(),
                img.getCaption(),
                img.isFeatured(),
                img.getSortOrder()
            ))
            .toList();

        List<String> amenities = room.getAmenities().stream()
            .map(RoomAmenity::getAmenityCode)
            .toList();

        return new SpaceCatalogDto(
            room.getId(),
            room.getCode(),
            room.getCentroCode(),
            room.getName(),
            room.getSubtitle(),
            room.getDescription(),
            room.getAddress(),
            room.getCity(),
            room.getPostalCode(),
            room.getCountry(),
            room.getRegion(),
            room.getType(),
            room.getStatus(),
            room.getCreationDate(),
            room.getSizeSqm(),
            room.getCapacity(),
            room.getPriceFrom(),
            room.getPriceUnit(),
            room.getPriceHourMin(),
            room.getPriceHourMed(),
            room.getPriceHourMax(),
            room.getPriceDay(),
            room.getPriceMonth(),
            room.getWifiCredentials(),
            room.getSortOrder(),
            room.getRatingAverage(),
            room.getRatingCount(),
            room.isInstantBooking(),
            tags,
            room.getHeroImage(),
            images,
            amenities
        );
    }
}
