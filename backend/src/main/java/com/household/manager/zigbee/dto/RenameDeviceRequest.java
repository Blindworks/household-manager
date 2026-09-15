package com.household.manager.zigbee.dto;

/** Body von PUT /v1/zigbee/devices/{ieee}/name. */
public record RenameDeviceRequest(String friendlyName) {
}
