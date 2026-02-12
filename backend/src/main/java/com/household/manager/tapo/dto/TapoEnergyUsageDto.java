package com.household.manager.tapo.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * DTO for Tapo device energy usage information.
 * Only available on devices with energy monitoring (e.g., P110).
 */
@Data
public class TapoEnergyUsageDto {

    @JsonProperty("today_runtime")
    private Integer todayRuntime;

    @JsonProperty("month_runtime")
    private Integer monthRuntime;

    @JsonProperty("today_energy")
    private Integer todayEnergy;

    @JsonProperty("month_energy")
    private Integer monthEnergy;

    @JsonProperty("current_power")
    private Integer currentPower;
}
