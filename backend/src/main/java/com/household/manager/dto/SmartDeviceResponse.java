package com.household.manager.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Data Transfer Object for smart device responses.
 * <p>
 * Provides complete device information including status, capabilities,
 * and device-specific metadata.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SmartDeviceResponse {

    /**
     * Unique identifier for the smart device record
     */
    private Long id;

    /**
     * Type of smart device (KASA, TAPO, or MEROSS)
     */
    private String deviceType;

    /**
     * Device's native identifier from the device ecosystem
     */
    private String externalDeviceId;

    /**
     * User-friendly name for the device
     */
    private String deviceName;

    /**
     * Device model identifier
     */
    private String model;

    /**
     * IP address for local network devices
     */
    private String ipAddress;

    /**
     * Current online/offline status of the device
     */
    @com.fasterxml.jackson.annotation.JsonProperty("isOnline")
    private boolean isOnline;

    /**
     * Current powered on/off state of the device
     */
    @com.fasterxml.jackson.annotation.JsonProperty("isPoweredOn")
    private boolean isPoweredOn;

    /**
     * List of device capabilities (e.g., "SWITCH", "DIMMER", "COLOR", "ENERGY_MONITOR")
     */
    private List<String> capabilities;

    /**
     * Device-specific metadata as a map.
     * <p>
     * Contains additional device properties that vary by device type.
     */
    private Map<String, Object> metadata;

    /**
     * Current brightness (1-100), read from the device's last known {@code get_device_info}.
     * {@code null} if the device doesn't report the {@code BRIGHTNESS} capability, or it hasn't
     * been probed since this field was added. Lets the frontend seed a slider with the bulb's
     * actual state instead of an invented default.
     */
    private Integer brightness;

    /** Current hue (0-360 degrees). {@code null} under the same conditions as {@link #brightness}. */
    private Integer hue;

    /** Current saturation (0-100 percent). {@code null} under the same conditions as {@link #brightness}. */
    private Integer saturation;

    /** Current colour temperature in Kelvin. {@code null} under the same conditions as {@link #brightness}. */
    private Integer colorTemp;

    /**
     * Timestamp when this record was created
     */
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime createdAt;

    /**
     * Timestamp when this record was last updated
     */
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime updatedAt;
}
