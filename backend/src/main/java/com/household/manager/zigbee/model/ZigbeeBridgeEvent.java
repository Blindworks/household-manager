package com.household.manager.zigbee.model;

import java.time.Instant;

/**
 * Ein Ereignis aus {@code zigbee2mqtt/bridge/event}: device_joined, device_interview
 * (status started/successful/failed), device_announce, device_leave.
 *
 * @param receivedAt vom Registry gesetzt, im Parser null
 */
public record ZigbeeBridgeEvent(
        String type,
        String friendlyName,
        String ieeeAddress,
        String status,
        String model,
        String vendor,
        Instant receivedAt) {

    public ZigbeeBridgeEvent at(Instant instant) {
        return new ZigbeeBridgeEvent(type, friendlyName, ieeeAddress, status, model, vendor, instant);
    }
}
