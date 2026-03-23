package com.household.manager.tapo;

public record TapoDiscoveryDevice(
        String ipAddress,
        TapoAuthProtocol authProtocol,
        String deviceId,
        String model,
        String nickname,
        boolean deviceOn
) {
}
