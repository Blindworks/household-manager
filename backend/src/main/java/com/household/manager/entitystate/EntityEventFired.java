package com.household.manager.entitystate;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Wird bei JEDEM Ereignis einer EVENT-Entität publiziert (z. B. Zigbee-Tastendruck) —
 * auch wenn die Aktion identisch zur vorherigen ist. Gegenstück zu
 * {@link EntityStateChangedEvent}, das nur Wertänderungen meldet.
 */
public record EntityEventFired(
        String entityId,
        String action,
        Map<String, Object> attributes,
        LocalDateTime timestamp
) {
}
