package com.household.manager.zigbee.dto;

import lombok.Builder;

import java.time.Instant;
import java.util.List;

/**
 * Zustand der Zigbee-Anbindung fuer das Frontend.
 */
@Builder
public record ZigbeeHealthResponse(
        String health,
        boolean healthy,
        Instant lastMessageAt,
        long silentMinutes,
        String bridgeState,
        List<String> offlineDevices) {
}
