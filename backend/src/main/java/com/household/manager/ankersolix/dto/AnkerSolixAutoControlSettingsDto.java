package com.household.manager.ankersolix.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Settings for the Anker Solix auto-control regulation.
 * Used for both reading and updating the configuration at runtime.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AnkerSolixAutoControlSettingsDto {

    /** Whether auto-control is enabled. */
    private boolean enabled;

    /** Minimum change in watts required to trigger an adjustment. */
    private int thresholdW;

    /** Polling interval in milliseconds. */
    private long intervalMs;
}
