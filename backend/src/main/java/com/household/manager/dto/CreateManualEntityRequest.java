package com.household.manager.dto;

import com.household.manager.entitystate.EntityDomain;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Anlage-Anfrage für eine manuelle Entität. Welche Felder ausgewertet werden,
 * hängt vom {@link #type} ab (Boolean/Number/Text/Select). Die Entity-ID wird aus
 * dem Namen abgeleitet und bleibt danach stabil.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateManualEntityRequest {

    /** Anzeigename; Grundlage der generierten Entity-ID. */
    @NotBlank(message = "Name is required")
    private String name;

    /** Helfer-Typ; fehlt der Wert, wird INPUT_BOOLEAN angenommen. */
    private EntityDomain type;

    /** Startwert; Interpretation je nach Typ (leer = sinnvoller Default). */
    private String state;

    /** Optionales Icon (z. B. Emoji oder Icon-Name) für die Anzeige. */
    private String icon;

    /** Auswahloptionen (nur INPUT_SELECT; dann Pflicht). */
    private List<String> options;

    /** Untergrenze (nur INPUT_NUMBER). */
    private Double min;

    /** Obergrenze (nur INPUT_NUMBER). */
    private Double max;

    /** Schrittweite (nur INPUT_NUMBER). */
    private Double step;

    /** Einheit für die Anzeige (nur INPUT_NUMBER, z. B. "°C"). */
    private String unit;
}
