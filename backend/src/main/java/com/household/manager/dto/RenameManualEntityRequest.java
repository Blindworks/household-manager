package com.household.manager.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Anfrage zum Umbenennen einer manuellen Entität. Die Entity-ID bleibt unverändert.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RenameManualEntityRequest {

    @NotBlank(message = "Name is required")
    private String name;

    /** Optionales Icon; wird bei Angabe aktualisiert. */
    private String icon;
}
