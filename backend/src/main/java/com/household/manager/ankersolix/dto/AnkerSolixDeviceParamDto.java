package com.household.manager.ankersolix.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Current operating parameters of the Anker Solix solarbank device.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AnkerSolixDeviceParamDto {

    /** Minimum configurable output power in watts. */
    private int minLoadW;

    /** Maximum configurable output power in watts. */
    private int maxLoadW;

    /** Currently configured output power in watts. */
    private int currentOutputW;
}
