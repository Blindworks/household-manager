package com.household.manager.flowengine.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Parst die JSON-Flow-Definition in das Modell. Wirft IllegalArgumentException
 * mit lesbarer Meldung bei kaputtem JSON (wird vom Deploy als 400 gemeldet).
 */
@Component
@RequiredArgsConstructor
public class FlowDefinitionParser {

    private final ObjectMapper objectMapper;

    public FlowDefinition parse(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            List<FlowNode> nodes = new ArrayList<>();
            for (JsonNode n : root.path("nodes")) {
                FlowNode.Position position = new FlowNode.Position(
                        n.path("position").path("x").asDouble(0),
                        n.path("position").path("y").asDouble(0));
                Map<String, Object> config = objectMapper.convertValue(
                        n.path("config").isMissingNode() ? objectMapper.createObjectNode() : n.path("config"),
                        objectMapper.getTypeFactory().constructMapType(Map.class, String.class, Object.class));
                nodes.add(new FlowNode(
                        n.path("id").asText(null),
                        n.path("type").asText(null),
                        n.path("name").asText(null),
                        position,
                        new NodeConfig(config)));
            }
            List<FlowWire> wires = new ArrayList<>();
            for (JsonNode w : root.path("wires")) {
                wires.add(new FlowWire(
                        new FlowWire.Endpoint(w.path("from").path("node").asText(null), w.path("from").path("port").asInt(0)),
                        new FlowWire.Target(w.path("to").path("node").asText(null))));
            }
            return new FlowDefinition(List.copyOf(nodes), List.copyOf(wires));
        } catch (Exception ex) {
            throw new IllegalArgumentException("Invalid flow definition: " + ex.getMessage(), ex);
        }
    }
}
