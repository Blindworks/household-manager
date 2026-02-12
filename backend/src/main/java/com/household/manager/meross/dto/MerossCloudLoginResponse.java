package com.household.manager.meross.dto;

public record MerossCloudLoginResponse(
        int apiStatus,
        int sysStatus,
        String message,
        String token,
        String userId,
        String key,
        String domain,
        String mqttDomain,
        Integer mfaLockExpire
) {
}
