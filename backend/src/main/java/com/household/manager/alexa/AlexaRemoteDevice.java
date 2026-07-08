package com.household.manager.alexa;

import java.util.List;

/**
 * Ein von der Alexa-Cloud gemeldetes Geraet.
 *
 * @param serialNumber  stabile Seriennummer (Identitaet)
 * @param accountName   Anzeigename
 * @param deviceType    Alexa-Geraetetyp-ID (z. B. A1TYPE)
 * @param deviceFamily  Familie (z. B. ROOK, ECHO)
 * @param capabilities  gemeldete Faehigkeiten
 */
public record AlexaRemoteDevice(
        String serialNumber,
        String accountName,
        String deviceType,
        String deviceFamily,
        List<String> capabilities) {

    /** true, wenn das Geraet Sprachausgabe unterstuetzt. */
    public boolean isTtsCapable() {
        if (capabilities == null) {
            return false;
        }
        return capabilities.contains("AUDIO_PLAYER") || capabilities.contains("TEXT_TO_SPEECH");
    }
}
