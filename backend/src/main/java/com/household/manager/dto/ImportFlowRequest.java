package com.household.manager.dto;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.Size;

/**
 * Body des Flow-Import-Endpoints. Die {@code definition} bleibt roher JsonNode
 * und wird als kompakter JSON-String an die Engine weitergereicht.
 */
public record ImportFlowRequest(
        Integer schemaVersion,
        @Size(max = FlowFieldLimits.NAME_MAX,
                message = "Name darf höchstens " + FlowFieldLimits.NAME_MAX + " Zeichen lang sein")
        String name,
        @Size(max = FlowFieldLimits.DESCRIPTION_MAX,
                message = "Beschreibung darf höchstens " + FlowFieldLimits.DESCRIPTION_MAX + " Zeichen lang sein")
        String description,
        @Size(max = FlowFieldLimits.CATEGORY_MAX,
                message = "Bereich darf höchstens " + FlowFieldLimits.CATEGORY_MAX + " Zeichen lang sein")
        String category,
        JsonNode definition) {
}
