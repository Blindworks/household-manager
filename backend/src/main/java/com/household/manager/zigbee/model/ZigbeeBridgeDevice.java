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

    /**
     * Batteriegeraete sind schlafende Endgeraete: andere Stille-Schwelle, kein Ping.
     * <p>
     * Konservativ: nur eine ausdruecklich netzversorgte Quelle ("Mains …", "DC Source")
     * gilt als Netzgeraet. Fehlt die Angabe oder lautet sie "Unknown", wird das Geraet
     * wie ein Batteriegeraet behandelt — sonst bekaeme es die aggressive 15-Minuten-Schwelle
     * und stuende bei jeder laengeren Funkpause faelschlich auf "still".
     */
    public boolean battery() {
        if (powerSource == null) {
            return true;
        }
        String source = powerSource.toLowerCase(Locale.ROOT);
        return !source.contains("mains") && !source.contains("dc source");
    }
}
