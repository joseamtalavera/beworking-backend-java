package com.beworking.rooms.catalog;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record SpaceCatalogDto(
    Long id,
    String code,
    String centroCode,
    String displayName,
    String subtitle,
    String description,
    String address,
    String city,
    String postalCode,
    String country,
    String region,
    String type,
    String status,
    LocalDate creationDate,
    Integer sizeSqm,
    Integer capacity,
    BigDecimal priceFrom,
    String priceUnit,
    BigDecimal priceHourMin,
    BigDecimal priceHourMed,
    BigDecimal priceHourMax,
    BigDecimal priceDay,
    BigDecimal priceMonth,
    String wifiCredentials,
    Integer sortOrder,
    BigDecimal ratingAverage,
    Integer ratingCount,
    Boolean instantBooking,
    List<String> tags,
    String heroImage,
    List<SpaceCatalogImageDto> images,
    List<String> amenities
) { }