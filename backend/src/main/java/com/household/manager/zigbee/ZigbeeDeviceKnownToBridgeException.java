package com.household.manager.zigbee;

/** "Aus Household Manager entfernen" fuer ein Geraet, das zigbee2mqtt noch kennt. Wird zu 409. */
public class ZigbeeDeviceKnownToBridgeException extends RuntimeException {
    public ZigbeeDeviceKnownToBridgeException(String message) {
        super(message);
    }
}
