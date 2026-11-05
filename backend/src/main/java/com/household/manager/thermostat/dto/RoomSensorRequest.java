package com.household.manager.thermostat.dto;

import jakarta.validation.constraints.Size;

/** Body von {@code PUT /v1/thermostats/{id}/room-sensor}; {@code sensorId} null oder leer entfernt die Zuordnung. */
public record RoomSensorRequest(@Size(max = 80) String sensorId) {
}
