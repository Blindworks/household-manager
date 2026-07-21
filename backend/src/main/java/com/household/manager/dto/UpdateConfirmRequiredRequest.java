package com.household.manager.dto;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Anfrage zum Setzen der Bestätigungspflicht eines Schalters.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UpdateConfirmRequiredRequest {

    @NotNull(message = "confirmRequired must not be null")
    private Boolean confirmRequired;
}
