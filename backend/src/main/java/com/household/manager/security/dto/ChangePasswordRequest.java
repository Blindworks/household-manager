package com.household.manager.security.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Selbst-Passwortwechsel des angemeldeten Nutzers. */
public record ChangePasswordRequest(@NotBlank String currentPassword,
                                    @NotBlank @Size(min = 8) String newPassword) {
}
