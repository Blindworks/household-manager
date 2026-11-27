package com.household.manager.tesla.dto;

import com.household.manager.tesla.TeslaCommand;
import jakarta.validation.constraints.NotNull;

public record TeslaCommandRequest(@NotNull TeslaCommand command) {
}
