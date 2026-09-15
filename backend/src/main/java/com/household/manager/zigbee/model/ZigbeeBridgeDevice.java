package com.household.manager.zigbee.model;

import java.util.Locale;

/**
 * Ein Geraet aus {@code zigbee2mqtt/bridge/devices} — zigbee2mqtt's eigenes Verzeichnis,
 * die Wahrheit darueber, welche Geraete gepaart sind (unsere Tabelle zigbee_device kennt
 * nur, was je gesendet hat).
 */
public record ZigbeeBridgeDevice(
        String ieeeAddress,
        String friendlyName,
        String type,
        String powerSource,
        boolean interviewCompleted,
        boolean supported,
        String model,
        String vendor,
        String description,
        Integer networkAddress) {

    /** Batteriegeraete sind schlafende Endgeraete: andere Stille-Schwelle, kein Ping. */
    public boolean battery() {
        return powerSource != null && powerSource.toLowerCase(Locale.ROOT).contains("battery");
    }
}
