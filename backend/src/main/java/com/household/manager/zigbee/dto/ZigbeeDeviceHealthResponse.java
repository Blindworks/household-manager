package com.household.manager.zigbee.dto;

import com.household.manager.zigbee.model.DeviceHealth;
import com.household.manager.zigbee.model.DeviceHealthStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ZigbeeDeviceHealthResponse {
    private DeviceHealthStatus status;
    private String basis;
    private Instant lastHeardAt;
    /** Sekunden seit lastHeardAt, null bei UNKNOWN. */
    private Long silentSeconds;

    public static ZigbeeDeviceHealthResponse from(DeviceHealth health) {
        return ZigbeeDeviceHealthResponse.builder()
                .status(health.status())
                .basis(health.basis())
                .lastHeardAt(health.lastHeardAt())
                .silentSeconds(health.silentFor() != null ? health.silentFor().toSeconds() : null)
                .build();
    }
}
