package com.household.manager.ankersolix.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Live snapshot of an Anker Solix power system.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AnkerSolixLiveDto {

    /** Current solar production in watts. */
    private double pvPowerW;

    /** Battery state of charge in percent (0–100). */
    private int batteryPercent;

    /**
     * Battery power in watts.
     * Positive = charging, negative = discharging.
     */
    private double batteryPowerW;

    /**
     * Grid power in watts.
     * Positive = drawing from grid, negative = feeding into grid.
     */
    private double gridPowerW;

    /** Current household consumption in watts. */
    private double homePowerW;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime timestamp;
}
