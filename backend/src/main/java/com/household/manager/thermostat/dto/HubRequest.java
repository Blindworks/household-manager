package com.household.manager.thermostat.dto;

import jakarta.validation.constraints.NotBlank;

/** Body von {@code PUT /v1/thermostats/hub}. */
public record HubRequest(@NotBlank String ip) {
}
