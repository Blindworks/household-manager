package com.household.manager.zigbee;

/** zigbee2mqtt hat den Request mit status error beantwortet; Nachricht = z2m's Fehlertext. Wird zu 400. */
public class ZigbeeBridgeRejectedException extends RuntimeException {
    public ZigbeeBridgeRejectedException(String message) {
        super(message);
    }
}
