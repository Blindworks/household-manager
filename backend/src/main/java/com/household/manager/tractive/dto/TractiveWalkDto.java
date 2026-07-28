package com.household.manager.tractive.dto;

import java.time.Instant;

/** Ein aus der Positionshistorie abgeleiteter Spaziergang. */
public record TractiveWalkDto(
        Instant start,
        Instant end,
        long durationMinutes,
        double distanceMeters
) {
}
