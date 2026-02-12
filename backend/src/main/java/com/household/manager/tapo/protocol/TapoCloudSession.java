package com.household.manager.tapo.protocol;

import lombok.Data;

/**
 * Represents a session with the Tapo Cloud API.
 */
@Data
public class TapoCloudSession {
    private final String token;
    private final String deviceId;
    private final long createdAt;

    public TapoCloudSession(String token, String deviceId) {
        this.token = token;
        this.deviceId = deviceId;
        this.createdAt = System.currentTimeMillis();
    }

    public boolean isExpired(long maxAgeMs) {
        return (System.currentTimeMillis() - createdAt) > maxAgeMs;
    }
}
