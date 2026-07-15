package com.household.manager.dto;

import lombok.Builder;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * API-Repräsentation einer Entität mit aktuellem Zustand.
 */
@Builder
public record EntityStateResponse(
        String entityId,
        String domain,
        String source,
        String sourceRef,
        String friendlyName,
        String customName,
        String displayName,
        String state,
        Map<String, Object> attributes,
        LocalDateTime lastChanged,
        LocalDateTime lastUpdated
) {
}
