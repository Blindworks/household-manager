package com.household.manager.flowengine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.household.manager.flowengine.model.FlowDefinitionParser;
import com.household.manager.flowengine.model.NodeConfig;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class FlowValidatorTest {

    /** Minimaler Test-Handler: Typ "test-action", 1 Ausgang, verlangt config.text. */
    private static class TestActionHandler implements NodeHandler {
        public String type() { return "test-action"; }
        public int outputPorts() { return 1; }
        public List<String> validate(NodeConfig config) {
            return config.string("text").isPresent() ? List.of() : List.of("text fehlt");
        }
        public NodeResult handle(FlowMessage m, NodeConfig c, NodeContext ctx) { return NodeResult.single(m); }
    }

    private static class TestTriggerHandler implements TriggerNodeHandler {
        public String type() { return "test-trigger"; }
        public int outputPorts() { return 1; }
        public List<String> validate(NodeConfig config) { return List.of(); }
        public Optional<String> watchedEntityId(NodeConfig config) { return Optional.empty(); }
    }

    private final FlowDefinitionParser parser = new FlowDefinitionParser(new ObjectMapper());
    private final com.household.manager.entitystate.EntityStateService entityStateService =
            org.mockito.Mockito.mock(com.household.manager.entitystate.EntityStateService.class);
    private final FlowValidator validator = new FlowValidator(
            List.of(new TestActionHandler(), new TestTriggerHandler()), entityStateService);

    private String def(String nodesJson, String wiresJson) {
        return "{ \"nodes\": [" + nodesJson + "], \"wires\": [" + wiresJson + "] }";
    }

    private static final String TRIGGER = "{ \"id\": \"t\", \"type\": \"test-trigger\", \"config\": {} }";
    private static final String ACTION = "{ \"id\": \"a\", \"type\": \"test-action\", \"config\": { \"text\": \"hi\" } }";

    @Test
    void validDefinitionHasNoErrors() {
        ValidationResult result = validator.validate(parser.parse(def(
                TRIGGER + "," + ACTION,
                "{ \"from\": { \"node\": \"t\", \"port\": 0 }, \"to\": { \"node\": \"a\" } }")));
        assertTrue(result.errors().isEmpty());
    }

    @Test
    void unknownNodeTypeIsAnError() {
        ValidationResult result = validator.validate(parser.parse(def(
                "{ \"id\": \"x\", \"type\": \"does-not-exist\", \"config\": {} }", "")));
        assertEquals(1, result.errors().size());
        assertTrue(result.errors().get(0).contains("does-not-exist"));
    }

    @Test
    void wireToMissingNodeAndInvalidPortAreErrors() {
        ValidationResult result = validator.validate(parser.parse(def(
                TRIGGER + "," + ACTION,
                "{ \"from\": { \"node\": \"t\", \"port\": 5 }, \"to\": { \"node\": \"ghost\" } }")));
        assertEquals(2, result.errors().size());
    }

    @Test
    void duplicateNodeIdAndMissingIdAreErrors() {
        ValidationResult result = validator.validate(parser.parse(def(
                TRIGGER + "," + TRIGGER + ", { \"type\": \"test-action\", \"config\": { \"text\": \"x\" } }", "")));
        assertFalse(result.errors().isEmpty());
    }

    @Test
    void nodeConfigErrorsArePrefixedWithNodeId() {
        ValidationResult result = validator.validate(parser.parse(def(
                "{ \"id\": \"a\", \"type\": \"test-action\", \"config\": {} }", "")));
        assertEquals(1, result.errors().size());
        assertTrue(result.errors().get(0).contains("a"));
        assertTrue(result.errors().get(0).contains("text fehlt"));
    }

    @Test
    void unknownEntityIdProducesWarningNotError() {
        org.mockito.Mockito.when(entityStateService.getByEntityId("sensor.ghost"))
                .thenReturn(Optional.empty());
        ValidationResult result = validator.validate(parser.parse(def(
                "{ \"id\": \"a\", \"type\": \"test-action\", \"config\": { \"text\": \"x\", \"entityId\": \"sensor.ghost\" } }", "")));

        assertTrue(result.errors().isEmpty());
        assertEquals(1, result.warnings().size());
        assertTrue(result.warnings().get(0).contains("sensor.ghost"));
    }
}
