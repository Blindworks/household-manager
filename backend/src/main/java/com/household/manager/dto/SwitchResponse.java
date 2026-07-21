package com.household.manager.dto;

import lombok.Builder;

import java.time.LocalDateTime;

/**
 * API-Repräsentation eines schaltbaren Eintrags für Schalter-Kachel und -Dialog.
 */
@Builder
public record SwitchResponse(
        String entityId,
        String domain,
        String source,
        String displayName,
        String state,
        boolean available,
        String icon,
        boolean confirmRequired,
        long toggleCount,
        LocalDateTime lastToggledAt
) {
}
