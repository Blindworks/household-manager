package com.household.manager.ankersolix.dto;

import java.time.LocalDateTime;

public record SolixAutoControlReadingResponse(
        Long id,
        LocalDateTime timestamp,
        Integer gridPowerW,
        Integer currentOutputW,
        Integer targetOutputW,
        Integer clampedOutputW,
        Integer minLoadW,
        Integer maxLoadW,
        boolean applied
) {
}
