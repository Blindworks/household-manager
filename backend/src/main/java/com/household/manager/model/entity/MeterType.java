package com.household.manager.model.entity;

/**
 * Enum representing the type of utility meter.
 * <p>
 * Supported meter types:
 * <ul>
 *   <li>ELECTRICITY - Electric meter (Stromzähler)</li>
 *   <li>GAS - Gas meter (Gaszähler)</li>
 *   <li>WATER - Water meter (Wasserzähler)</li>
 * </ul>
 */
public enum MeterType {
    /**
     * Electric meter (Stromzähler) - typically measured in kWh
     */
    ELECTRICITY,

    /**
     * Gas meter (Gaszähler) - typically measured in m³
     */
    GAS,

    /**
     * Water meter (Wasserzähler) - typically measured in m³
     */
    WATER
}
