package com.household.manager.entitystate;

import lombok.Builder;

import java.util.Map;

/**
 * Zustandsmeldung einer Integration an die Entity-Schicht.
 * Unbekannte Entity-IDs werden automatisch registriert (Upsert).
 */
@Builder
public record EntityStateUpdate(
        String entityId,
        EntityDomain domain,
        EntitySource source,
        String sourceRef,
        String friendlyName,
        String state,
        Map<String, Object> attributes
) {
}
