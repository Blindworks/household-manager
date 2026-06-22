package com.household.manager.zigbee.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ZigbeeDeviceResponse {
    private Long id;
    private String friendlyName;
    private String ieeeAddress;
    private String deviceType;
    private String model;
    private Integer lastBatteryPercent;
    private Integer lastLinkQuality;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime lastSeen;
}
