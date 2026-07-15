package com.household.manager.dto;

import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Anfrage zum Setzen oder Löschen des Kurznamens einer Entität.
 * Ein leerer/null Wert löscht den Kurznamen (Fallback auf friendlyName).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UpdateEntityCustomNameRequest {

    @Size(max = 255, message = "Custom name must not exceed 255 characters")
    private String customName;
}
