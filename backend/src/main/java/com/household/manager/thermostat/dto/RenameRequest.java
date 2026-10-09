package com.household.manager.thermostat.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Body von {@code PUT /v1/thermostats/{id}} (Umbenennen). */
public record RenameRequest(@NotBlank @Size(max = 100) String name) {
}
