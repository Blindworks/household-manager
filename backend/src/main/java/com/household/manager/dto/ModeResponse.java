package com.household.manager.dto;

import lombok.Builder;

/** API-Repräsentation eines Haus-Modus für die Modus-Leiste des Dashboards. */
@Builder
public record ModeResponse(
        String entityId,
        String displayName,
        String icon,
        String state
) {
}
