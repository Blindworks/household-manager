package com.household.manager.tapo.dto;

import lombok.Builder;

@Builder
public record TapoDiscoveryDeviceResponse(
        String deviceId,
        String deviceName,
        String deviceType,
        String model,
        String ip,
        Boolean online,
        String fwVer
) {}
