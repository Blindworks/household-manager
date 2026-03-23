package com.household.manager.shelly.dto;

public record ShellyStatusDto(
        String deviceName,
        String ip,
        boolean output,
        Double power,
        Double voltage,
        Double current,
        Double totalEnergy,
        boolean reachable
) {}
