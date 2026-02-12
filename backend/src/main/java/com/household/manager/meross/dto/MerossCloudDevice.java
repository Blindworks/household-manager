package com.household.manager.meross.dto;

public record MerossCloudDevice(
        String uuid,
        String devName,
        String deviceType,
        String onlineStatus,
        String domain,
        String reservedDomain
) {
}
