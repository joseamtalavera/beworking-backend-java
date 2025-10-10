package com.beworking.bookings;

import java.util.List;

public record CreateReservaResponse(Long reservaId, List<BloqueoResponse> bloqueos) {
}
