package com.household.manager.zigbee.dto;

/** Ein Flow, der eine Entitaet des Geraets verwendet (Draft oder Deployed). */
public record FlowReferenceResponse(Long flowId, String name, boolean enabled) {
}
