package com.beworking.contacts;

import java.util.List;

public record ContactProfilesPageResponse(
    List<ContactProfileResponse> items,
    int page,
    int size,
    long totalElements,
    int totalPages,
    boolean hasNext,
    boolean hasPrevious
) {}
