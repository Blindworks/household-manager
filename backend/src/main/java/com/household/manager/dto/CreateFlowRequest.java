package com.household.manager.dto;

import jakarta.validation.constraints.Size;

public record CreateFlowRequest(
        @Size(max = FlowFieldLimits.NAME_MAX,
                message = "Name darf höchstens " + FlowFieldLimits.NAME_MAX + " Zeichen lang sein")
        String name,
        @Size(max = FlowFieldLimits.DESCRIPTION_MAX,
                message = "Beschreibung darf höchstens " + FlowFieldLimits.DESCRIPTION_MAX + " Zeichen lang sein")
        String description) {
}
