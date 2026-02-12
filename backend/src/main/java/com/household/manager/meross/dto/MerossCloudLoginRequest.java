package com.household.manager.meross.dto;

import jakarta.validation.constraints.NotBlank;

public record MerossCloudLoginRequest(
        @NotBlank String email,
        @NotBlank String password,
        @NotBlank String countryCode,
        String region
) {
}
