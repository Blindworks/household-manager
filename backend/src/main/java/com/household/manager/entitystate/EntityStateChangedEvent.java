package com.household.manager.entitystate;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Wird publiziert, wenn sich der Zustandswert einer Entität geändert hat
 * (nicht bei bloßer Aktualisierung ohne Wertänderung).
 * Grundstein für die spätere Regel-Engine: dort einfach per @EventListener konsumieren.
 */
public record EntityStateChangedEvent(
        String entityId,
        String oldState,
        String newState,
        Map<String, Object> attributes,
        LocalDateTime timestamp
) {
}
