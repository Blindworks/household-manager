package com.household.manager.zigbee;

/** zigbee2mqtt nicht erreichbar: keine MQTT-Verbindung, Publish gescheitert oder Timeout. Wird zu 502. */
public class ZigbeeBridgeUnavailableException extends RuntimeException {
    public ZigbeeBridgeUnavailableException(String message) {
        super(message);
    }
}
