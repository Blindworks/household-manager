package com.household.manager.dto;

import java.math.BigDecimal;

/**
 * Ein Stromverbraucher für die Verbraucher-Kachel: Power-Sensor einer
 * Steckdose (Meross, Shelly, ...) mit aktueller Leistung.
 */
public record PowerConsumerResponse(
        String entityId,
        String displayName,
        /** Aktuelle Leistung in Watt; null, wenn der Sensor nicht erreichbar ist. */
        BigDecimal powerWatts,
        boolean unavailable
) {
}
