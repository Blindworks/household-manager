package com.household.manager.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * Data Transfer Object for updating a smart device.
 * <p>
 * Allows modification of device name and metadata.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SmartDeviceUpdateRequest {

    /**
     * Updated user-friendly name for the device
     */
    @NotBlank(message = "Device name cannot be blank")
    private String deviceName;

    /**
     * Updated device-specific metadata.
     * <p>
     * Optional field; if provided, replaces the existing metadata.
     */
    private Map<String, Object> metadata;
}
