package com.beworking.rooms.catalog;

public record SpaceCatalogImageDto(
    Long id,
    String url,
    String caption,
    Boolean featured,
    Integer sortOrder
) { }
