package com.household.manager.flowengine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.household.manager.flowengine.model.FlowDefinitionParser;
import com.household.manager.flowengine.model.NodeConfig;
import com.household.manager.flowengine.nodes.DelayNodeHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class FlowEngineTest {

    private final List<String> received = new CopyOnWriteArrayList<>();

    /** Sammelt empfangene Messages; 1 Ausgang, reicht weiter. */
    private class RecordingHandler implements NodeHandler {
        public String type() { return "recorder"; }
        public int outputPorts() { return 1; }
        public List<String> validate(NodeConfig config) { return List.of(); }
        public NodeResult handle(FlowMessage m, NodeConfig c, NodeContext ctx) {
            received.add(ctx.nodeId() + ":" + m.get("v"));
            return NodeResult.single(m);
        }
    }

    /** Router: schickt auf Port 0 oder 1 je nach config.port. */
    private static class RouterHandler implements NodeHandler {
        public String type() { return "router"; }
        public int outputPorts() { return 2; }
        public List<String> validate(NodeConfig config) { return List.of(); }
        public NodeResult handle(FlowMessage m, NodeConfig c, NodeContext ctx) {
            return NodeResult.port(c.integer("port").orElse(0), m);
        }
    }

    /** Wirft immer. */
    private static class FailingHandler implements NodeHandler {
        public String type() { return "failing"; }
        public int outputPorts() { return 1; }
        public List<String> validate(NodeConfig config) { return List.of(); }
        public NodeResult handle(FlowMessage m, NodeConfig c, NodeContext ctx) {
            throw new IllegalStateException("boom");
        }
    }

    private static class TestTriggerHandler implements TriggerNodeHandler {
        public String type() { return "test-trigger"; }
        public int outputPorts() { return 1; }
        public List<String> validate(NodeConfig config) { return List.of(); }
        public Optional<String> watchedEntityId(NodeConfig config) { return Optional.of("sensor.x"); }
    }

    private FlowEngine engine;
    private FlowRegistry registry;
    private final FlowDefinitionParser parser = new FlowDefinitionParser(new ObjectMapper());

    @BeforeEach
    void setUp() {
        List<NodeHandler> handlers = List.of(
                new RecordingHandler(), new RouterHandler(), new FailingHandler(), new TestTriggerHandler());
        registry = new FlowRegistry(handlers);
        // Synchroner "Executor" (Runnable::run) macht die Tests deterministisch.
        engine = new FlowEngine(registry, Runnable::run,
                mock(org.springframework.scheduling.TaskScheduler.class), new DebugBuffer());
        registry.setEngine(engine);
    }

    private void deploy(long flowId, String json) {
        registry.deploy(flowId, parser.parse(json));
    }

    @Test
    void traversesLinearChain() {
        deploy(1L, """
                { "nodes": [
                    { "id": "t", "type": "test-trigger", "config": {} },
                    { "id": "r1", "type": "recorder", "config": {} },
                    { "id": "r2", "type": "recorder", "config": {} } ],
                  "wires": [
                    { "from": { "node": "t", "port": 0 }, "to": { "node": "r1" } },
                    { "from": { "node": "r1", "port": 0 }, "to": { "node": "r2" } } ] }
                """);

        engine.runFrom(1L, "t", 0, FlowMessage.of(Map.of("v", "1")));

        assertEquals(List.of("r1:1", "r2:1"), received);
    }

    @Test
    void routerSendsOnlyToWiredPort() {
        deploy(1L, """
                { "nodes": [
                    { "id": "t", "type": "test-trigger", "config": {} },
                    { "id": "rt", "type": "router", "config": { "port": 1 } },
                    { "id": "yes", "type": "recorder", "config": {} },
                    { "id": "no", "type": "recorder", "config": {} } ],
                  "wires": [
                    { "from": { "node": "t", "port": 0 }, "to": { "node": "rt" } },
                    { "from": { "node": "rt", "port": 0 }, "to": { "node": "yes" } },
                    { "from": { "node": "rt", "port": 1 }, "to": { "node": "no" } } ] }
                """);

        engine.runFrom(1L, "t", 0, FlowMessage.of(Map.of("v", "1")));

        assertEquals(List.of("no:1"), received);
    }

    @Test
    void failingNodeAbortsOnlyItsBranch() {
        deploy(1L, """
                { "nodes": [
                    { "id": "t", "type": "test-trigger", "config": {} },
                    { "id": "bad", "type": "failing", "config": {} },
                    { "id": "afterBad", "type": "recorder", "config": {} },
                    { "id": "good", "type": "recorder", "config": {} } ],
                  "wires": [
                    { "from": { "node": "t", "port": 0 }, "to": { "node": "bad" } },
                    { "from": { "node": "t", "port": 0 }, "to": { "node": "good" } },
                    { "from": { "node": "bad", "port": 0 }, "to": { "node": "afterBad" } } ] }
                """);

        engine.runFrom(1L, "t", 0, FlowMessage.of(Map.of("v", "1")));

        assertEquals(List.of("good:1"), received);
    }

    @Test
    void hopLimitStopsCycles() {
        deploy(1L, """
                { "nodes": [
                    { "id": "t", "type": "test-trigger", "config": {} },
                    { "id": "a", "type": "recorder", "config": {} },
                    { "id": "b", "type": "recorder", "config": {} } ],
                  "wires": [
                    { "from": { "node": "t", "port": 0 }, "to": { "node": "a" } },
                    { "from": { "node": "a", "port": 0 }, "to": { "node": "b" } },
                    { "from": { "node": "b", "port": 0 }, "to": { "node": "a" } } ] }
                """);

        engine.runFrom(1L, "t", 0, FlowMessage.of(Map.of("v", "1")));

        assertEquals(100, received.size());
    }

    @Test
    void undeployedFlowIsIgnored() {
        deploy(1L, """
                { "nodes": [
                    { "id": "t", "type": "test-trigger", "config": {} },
                    { "id": "r1", "type": "recorder", "config": {} } ],
                  "wires": [ { "from": { "node": "t", "port": 0 }, "to": { "node": "r1" } } ] }
                """);
        registry.undeploy(1L);

        engine.runFrom(1L, "t", 0, FlowMessage.of(Map.of("v", "1")));

        assertTrue(received.isEmpty());
    }

    @Test
    void perNodeStateSurvivesAcrossExecutionsButNotRedeploy() {
        String def = """
                { "nodes": [
                    { "id": "t", "type": "test-trigger", "config": {} },
                    { "id": "r1", "type": "recorder", "config": {} } ],
                  "wires": [ { "from": { "node": "t", "port": 0 }, "to": { "node": "r1" } } ] }
                """;
        deploy(1L, def);
        registry.context(1L, "r1").state().put("k", "v");
        assertEquals("v", registry.context(1L, "r1").state().get("k"));

        deploy(1L, def); // Re-Deploy
        assertNull(registry.context(1L, "r1").state().get("k"));
    }

    @Test
    void undeployCancelsPendingDelayFutures() {
        List<NodeHandler> handlers = List.of(
                new RecordingHandler(), new RouterHandler(), new FailingHandler(),
                new TestTriggerHandler(), new DelayNodeHandler());
        FlowRegistry reg = new FlowRegistry(handlers);
        org.springframework.scheduling.TaskScheduler delayScheduler =
                mock(org.springframework.scheduling.TaskScheduler.class);
        ScheduledFuture<?> future = mock(ScheduledFuture.class);
        doReturn(future).when(delayScheduler).schedule(any(Runnable.class), any(Instant.class));
        FlowEngine delayEngine = new FlowEngine(reg, Runnable::run, delayScheduler, new DebugBuffer());
        reg.setEngine(delayEngine);

        reg.deploy(2L, parser.parse("""
                { "nodes": [
                    { "id": "t", "type": "test-trigger", "config": {} },
                    { "id": "d", "type": "delay", "config": { "seconds": 300 } } ],
                  "wires": [ { "from": { "node": "t", "port": 0 }, "to": { "node": "d" } } ] }
                """));

        delayEngine.runFrom(2L, "t", 0, FlowMessage.of(Map.of("v", "1")));

        reg.undeploy(2L);

        verify(future).cancel(false);
    }
}
