package com.household.manager.zigbee.model;

import java.time.Instant;
import java.util.List;

/**
 * Urteil ueber den Zustand der Zigbee-Anbindung.
 *
 * @param health         Gesamturteil
 * @param lastMessageAt  wann kam zuletzt irgendeine Geraetenachricht
 * @param silentMinutes  wie lange ist es seitdem still
 * @param bridgeState    letzter von zigbee2mqtt gemeldeter Zustand, oder null
 * @param offlineDevices Geraete, die zigbee2mqtt als offline meldet
 */
public record ZigbeeStreamStatus(
        Health health,
        Instant lastMessageAt,
        long silentMinutes,
        String bridgeState,
        List<String> offlineDevices) {

    public enum Health {
        /** Nachrichten kommen an. */
        OK,
        /** Keine Nachricht innerhalb der Schwelle. */
        STILL,
        /** zigbee2mqtt meldet sich selbst als offline. */
        BRIDGE_OFFLINE
    }

    public boolean healthy() {
        return health == Health.OK;
    }
}
