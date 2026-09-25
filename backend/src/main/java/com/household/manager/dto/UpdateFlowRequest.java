package com.household.manager.dto;

import jakarta.validation.constraints.Size;

/**
 * Teil-Update: fehlende Felder bleiben unverändert. {@code category: ""} entfernt den Bereich.
 */
public record UpdateFlowRequest(
        @Size(max = FlowFieldLimits.NAME_MAX,
                message = "Name darf höchstens " + FlowFieldLimits.NAME_MAX + " Zeichen lang sein")
        String name,
        @Size(max = FlowFieldLimits.DESCRIPTION_MAX,
                message = "Beschreibung darf höchstens " + FlowFieldLimits.DESCRIPTION_MAX + " Zeichen lang sein")
        String description,
        @Size(max = FlowFieldLimits.CATEGORY_MAX,
                message = "Bereich darf höchstens " + FlowFieldLimits.CATEGORY_MAX + " Zeichen lang sein")
        String category,
        String draftDefinition) {
}
