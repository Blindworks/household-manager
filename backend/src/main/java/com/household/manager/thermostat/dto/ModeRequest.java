package com.household.manager.thermostat.dto;

import com.household.manager.tapo.hub.ThermostatMode;
import jakarta.validation.constraints.NotNull;

/** Body von {@code PUT /v1/thermostats/{id}/mode}. */
public record ModeRequest(@NotNull ThermostatMode mode) {
}
