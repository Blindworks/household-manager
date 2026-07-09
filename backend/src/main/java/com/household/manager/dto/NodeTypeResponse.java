package com.household.manager.dto;

import lombok.Builder;

import java.util.Map;

@Builder
public record NodeTypeResponse(String type, int outputPorts, boolean trigger, Map<String, String> configSchema) {
}
