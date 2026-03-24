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

    /** Whether force-discharge logic is enabled (forces battery discharge when grid import detected). */
    private boolean forceDischargeEnabled;

    /** Battery SOC threshold in percent – force-discharge is disabled below this value. */
    private int forceDischargeMinBatteryPercent;

    /** Offset in watts added to the target calculation to keep grid power slightly positive instead of zero. */
    private int gridPowerOffsetW;

    /** Whether to turn off the battery entirely when target output falls below the device minimum load. */
    private boolean batteryPowerCutoffEnabled;
}
