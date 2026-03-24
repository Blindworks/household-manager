package com.household.manager.tapo.dto;

import lombok.Builder;

@Builder
public record TapoDeviceInfoResponse(
        String deviceId,
        String type,
        String model,
        String hwVer,
        String fwVer,
        String mac,
        String nickname,
        Boolean deviceOn,
        Integer onTime,
        Integer signalLevel,
        Integer rssi,
        String ip,
        String ssid,
        Boolean overheated
) {}
