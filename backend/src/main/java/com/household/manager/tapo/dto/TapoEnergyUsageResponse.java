package com.household.manager.tapo.dto;

import lombok.Builder;

@Builder
public record TapoEnergyUsageResponse(
        Integer todayRuntime,
        Integer monthRuntime,
        Integer todayEnergy,
        Integer monthEnergy,
        Integer currentPower
) {}
