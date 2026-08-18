package com.household.manager.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Data Transfer Object for setting a Tapo light's brightness, colour and/or colour temperature.
 * <p>
 * Every field is optional — only fields actually set are applied to the device (the rest are
 * left untouched). Range and capability validation is deliberately not expressed here as bean
 * validation: whether a field is applicable at all depends on the device's own reported
 * capabilities, and the valid {@code colorTemp} range is device-specific (read from the device's
 * own {@code get_device_info} response). Both checks happen in
 * {@link com.household.manager.service.SmartDeviceService#setLightState}, at the point where the
 * device's stored capabilities and metadata are available.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LightStateRequest {

    /** 1-100. Requires the device to report the {@code BRIGHTNESS} capability. */
    private Integer brightness;

    /** 0-360 degrees. Requires the device to report the {@code COLOR} capability. */
    private Integer hue;

    /** 0-100 percent. Requires the device to report the {@code COLOR} capability. */
    private Integer saturation;

    /**
     * Kelvin. Requires the device to report the {@code COLOR_TEMP} capability; valid range is
     * device-specific (falls back to 2500-6500 if the device never reported its own range).
     */
    private Integer colorTemp;
}
