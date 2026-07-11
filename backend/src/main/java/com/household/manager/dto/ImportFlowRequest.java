package com.household.manager.dto;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Body des Flow-Import-Endpoints. Die {@code definition} bleibt roher JsonNode
 * und wird als kompakter JSON-String an die Engine weitergereicht.
 */
public record ImportFlowRequest(Integer schemaVersion, String name, String description, JsonNode definition) {
}
