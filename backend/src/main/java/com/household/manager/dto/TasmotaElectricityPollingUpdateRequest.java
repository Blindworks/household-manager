package com.household.manager.dto;

import jakarta.validation.constraints.Min;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Update request for Tasmota electricity polling configuration.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TasmotaElectricityPollingUpdateRequest {

    /**
     * Enable or disable polling.
     */
    private Boolean enabled;

    /**
     * Polling interval in milliseconds.
     */
    @Min(value = 1000, message = "Interval must be at least 1000 ms")
    private Long intervalMs;

    /**
     * Tasmota status URL.
     */
    private String url;
}
