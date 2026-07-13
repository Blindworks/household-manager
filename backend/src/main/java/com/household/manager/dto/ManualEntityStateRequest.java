package com.household.manager.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Anfrage zum Setzen des Zustands einer manuellen Boolean-Entität ("on"/"off").
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ManualEntityStateRequest {

    @NotBlank(message = "State is required")
    private String state;
}
