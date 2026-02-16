package com.household.manager.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Data Transfer Object for requesting a device scan.
 * <p>
 * Specifies which type of devices to scan for on the network.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SmartDeviceScanRequest {

    /**
     * Type of devices to scan for (KASA, TAPO, or MEROSS)
     */
    @NotNull(message = "Device type is required")
    @Pattern(regexp = "KASA|TAPO|MEROSS", message = "Device type must be KASA, TAPO, or MEROSS")
    private String deviceType;
}
