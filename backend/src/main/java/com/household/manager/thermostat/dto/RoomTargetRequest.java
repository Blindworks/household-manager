package com.household.manager.thermostat.dto;

/** Body von {@code PUT /v1/thermostats/{id}/room-target}; {@code roomTarget} null beendet die Raumregelung. */
public record RoomTargetRequest(Double roomTarget) {
}
