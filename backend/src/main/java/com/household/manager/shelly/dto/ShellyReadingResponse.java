package com.household.manager.shelly.dto;

import java.time.LocalDateTime;

public record ShellyReadingResponse(
        Long id,
        String deviceName,
        LocalDateTime timestamp,
        Double power,
        Double voltage,
        Double current,
        Double totalEnergy
) {}
