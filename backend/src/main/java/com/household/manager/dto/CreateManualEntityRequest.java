package com.household.manager.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Anlage-Anfrage für eine manuelle Boolean-Entität (z. B. "Nachtmodus").
 * Die Entity-ID wird aus dem Namen abgeleitet und bleibt danach stabil.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateManualEntityRequest {

    /** Anzeigename; Grundlage der generierten Entity-ID. */
    @NotBlank(message = "Name is required")
    private String name;

    /** Startzustand ("on"/"off"); leer bedeutet "off". */
    private String state;

    /** Optionales Icon (z. B. Emoji oder Icon-Name) für die Anzeige. */
    private String icon;
}
