package com.household.manager.zigbee.model;

import java.time.Instant;

/**
 * Auszug aus {@code zigbee2mqtt/bridge/info}.
 *
 * @param permitJoinEnd Ende des Anlernfensters, null wenn geschlossen (z2m 2.x liefert
 *                      das Feld nur bei offenem Fenster)
 * @param availabilityCheckEnabled ob zigbee2mqtt's Verfuegbarkeitspruefung aktiv ist —
 *                                 ohne sie kommen nie {@code <name>/availability}-Meldungen
 */
public record ZigbeeBridgeInfo(
        String version,
        boolean permitJoin,
        Instant permitJoinEnd,
        boolean availabilityCheckEnabled) {
}
