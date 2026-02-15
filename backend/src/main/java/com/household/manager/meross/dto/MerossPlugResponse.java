package com.household.manager.meross.dto;

public record MerossPlugResponse(
        String deviceId,
        String name,
        String deviceType,
        boolean on,
        String onlineStatus
) {
}
