package com.household.manager.thermostat.dto;

import jakarta.validation.constraints.NotNull;

/** Body von {@code PUT /v1/thermostats/{id}/target}; Wertebereich prueft der Service. */
public record TargetRequest(@NotNull Double targetTemp) {
}
