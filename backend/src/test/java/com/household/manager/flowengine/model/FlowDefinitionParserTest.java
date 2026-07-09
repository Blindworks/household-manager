package com.household.manager.flowengine.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class FlowDefinitionParserTest {

    private final FlowDefinitionParser parser = new FlowDefinitionParser(new ObjectMapper());

    private static final String JSON = """
            {
              "nodes": [
                { "id": "n1", "type": "entity-state-trigger", "name": "Waschmaschine",
                  "position": { "x": 80, "y": 120 },
                  "config": { "entityId": "sensor.x_power", "operator": "<", "value": "5", "forSeconds": 180 } },
                { "id": "n2", "type": "alexa-announce", "position": { "x": 420, "y": 120 },
                  "config": { "text": "Fertig", "mode": "ANNOUNCE", "deviceSerials": ["G09"] } }
              ],
              "wires": [ { "from": { "node": "n1", "port": 0 }, "to": { "node": "n2" } } ]
            }
            """;

    @Test
    void parsesNodesWiresAndConfig() {
        FlowDefinition def = parser.parse(JSON);

        assertEquals(2, def.nodes().size());
        FlowNode n1 = def.nodes().get(0);
        assertEquals("n1", n1.id());
        assertEquals("entity-state-trigger", n1.type());
        assertEquals(Optional.of("sensor.x_power"), n1.config().string("entityId"));
        assertEquals(Optional.of(180), n1.config().integer("forSeconds"));

        assertEquals(1, def.wires().size());
        assertEquals("n1", def.wires().get(0).from().node());
        assertEquals(0, def.wires().get(0).from().port());
        assertEquals("n2", def.wires().get(0).to().node());
    }

    @Test
    void configStringListAndMissingKeys() {
        FlowDefinition def = parser.parse(JSON);
        NodeConfig config = def.nodes().get(1).config();

        assertEquals(java.util.List.of("G09"), config.stringList("deviceSerials"));
        assertTrue(config.string("missing").isEmpty());
        assertTrue(config.integer("missing").isEmpty());
        assertTrue(config.stringList("missing").isEmpty());
    }

    @Test
    void invalidJsonThrowsWithReadableMessage() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> parser.parse("{ kaputt"));
        assertTrue(ex.getMessage().contains("Invalid flow definition"));
    }

    @Test
    void integerAcceptsNumericStrings() {
        FlowDefinition def = parser.parse(JSON.replace("\"forSeconds\": 180", "\"forSeconds\": \"180\""));
        assertEquals(Optional.of(180), def.nodes().get(0).config().integer("forSeconds"));
    }
}
