package com.household.manager.thermostat.dto;

import jakarta.validation.constraints.NotNull;

import java.util.List;

/** Body von {@code POST /v1/thermostats/bulk}; {@code ids} fehlt oder ist null = alle aktiven. */
public record BulkRequest(List<Long> ids, @NotNull Double targetTemp) {
}
