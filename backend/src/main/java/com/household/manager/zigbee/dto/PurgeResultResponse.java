package com.household.manager.zigbee.dto;

/** Antwort von DELETE /v1/zigbee/devices/local/{id}. */
public record PurgeResultResponse(String friendlyName, int measurements, int entities) {
}
