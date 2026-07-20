package com.household.manager.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Anfrage zum Setzen der Kachel-Sichtbarkeit einer Entität.
 * "AUTO" entfernt die Regel (Standardverhalten).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UpdateTileVisibilityRequest {

    @NotBlank(message = "Visibility must not be blank")
    private String visibility;
}
