package com.household.manager.flowengine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.household.manager.flowengine.model.FlowDefinitionParser;
import com.household.manager.flowengine.model.NodeConfig;
import com.household.manager.systemlog.SystemLogStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.scheduling.TaskScheduler;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class FlowEngineRunTraceTest {

    private final List<String> received = new CopyOnWriteArrayList<>();
    private final List<Object> seenRunIds = new CopyOnWriteArrayList<>();

    private class ActionHandler implements NodeHandler {
        public String type() { return "action"; }
        public int outputPorts() { return 1; }
        public List<String> validate(NodeConfig config) { return List.of(); }
        public NodeResult handle(FlowMessage m, NodeConfig c, NodeContext ctx) {
            received.add(ctx.nodeId());
            seenRunIds.add(m.get(FlowRunTrace.RUN_ID_KEY));
            return NodeResult.single(m);
        }
    }

    private static class TestConditionHandler implements ConditionNodeHandler {
        public String type() { return "cond"; }
        public int outputPorts() { return 2; }
        public List<String> validate(NodeConfig config) { return List.of(); }
        public NodeResult handle(FlowMessage m, NodeConfig c, NodeContext ctx) {
            return NodeResult.port(c.integer("port").orElse(0), m);
        }
    }

    private static class TestGateHandler implements GateNodeHandler {
        public String type() { return "gate"; }
        public int outputPorts() { return 1; }
        public List<String> validate(NodeConfig config) { return List.of(); }
        public NodeResult handle(FlowMessage m, NodeConfig c, NodeContext ctx) {
            return NodeResult.none();
        }
    }

    private static class TestDeferringHandler implements DeferringNodeHandler {
        public String type() { return "defer"; }
        public int outputPorts() { return 1; }
        public List<String> validate(NodeConfig config) { return List.of(); }
        public NodeResult handle(FlowMessage m, NodeConfig c, NodeContext ctx) {
            return NodeResult.none();
        }
    }

    private static class FailingHandler implements NodeHandler {
        public String type() { return "failing"; }
        public int outputPorts() { return 1; }
        public List<String> validate(NodeConfig config) { return List.of(); }
        public NodeResult handle(FlowMessage m, NodeConfig c, NodeContext ctx) {
            throw new IllegalStateException("boom");
        }
    }

    private static class ReportingHandler implements NodeHandler {
        public String type() { return "reporting"; }
        public int outputPorts() { return 1; }
        public List<String> validate(NodeConfig config) { return List.of(); }
        public NodeResult handle(FlowMessage m, NodeConfig c, NodeContext ctx) {
            ctx.reportFailure("Telegram 502");
            return NodeResult.single(m);
        }
    }

    /** Setzt synchron (Runnable::run) einen zweiten Lauf an und meldet danach einen Fehler. */
    private static class EmitThenReportHandler implements NodeHandler {
        public String type() { return "emit-report"; }
        public int outputPorts() { return 1; }
        public List<String> validate(NodeConfig config) { return List.of(); }
        public NodeResult handle(FlowMessage m, NodeConfig c, NodeContext ctx) {
            ctx.emit(0, m);
            ctx.reportFailure("nach Fortsetzung");
            return NodeResult.none();
        }
    }

    private static class TestTriggerHandler implements TriggerNodeHandler {
        public String type() { return "test-trigger"; }
        public int outputPorts() { return 1; }
        public List<String> validate(NodeConfig config) { return List.of(); }
        public Optional<String> watchedEntityId(NodeConfig config) { return Optional.of("sensor.x"); }
    }

    private final FlowRunRecorder runRecorder = mock(FlowRunRecorder.class);
    private final FlowDefinitionParser parser = new FlowDefinitionParser(new ObjectMapper());
    private FlowRegistry registry;
    private FlowEngine engine;

    @BeforeEach
    void setUp() {
        registry = new FlowRegistry(List.of(new ActionHandler(), new TestConditionHandler(), new TestGateHandler(),
                new TestDeferringHandler(), new FailingHandler(), new ReportingHandler(), new EmitThenReportHandler(), new TestTriggerHandler()));
        engine = new FlowEngine(registry, Runnable::run, mock(TaskScheduler.class), new DebugBuffer(),
                mock(FlowTriggerRecorder.class), runRecorder);
        registry.setEngine(engine);
    }

    private void deploy(String json) {
        registry.deploy(1L, parser.parse(json));
    }

    private void runFromTrigger() {
        engine.runFrom(1L, "t", 0, FlowMessage.of(Map.of("v", "1")));
    }

    private record Recorded(String runId, SystemLogStatus status, List<FlowRunTrace.Step> steps,
                            boolean continuation, boolean injected) {
    }

    @SuppressWarnings("unchecked")
    private Recorded recorded() {
        ArgumentCaptor<String> runId = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<SystemLogStatus> status = ArgumentCaptor.forClass(SystemLogStatus.class);
        ArgumentCaptor<List<FlowRunTrace.Step>> steps = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<Boolean> continuation = ArgumentCaptor.forClass(Boolean.class);
        ArgumentCaptor<Boolean> injected = ArgumentCaptor.forClass(Boolean.class);
        verify(runRecorder).record(eq(1L), runId.capture(), status.capture(), steps.capture(),
                continuation.capture(), injected.capture());
        return new Recorded(runId.getValue(), status.getValue(), steps.getValue(),
                continuation.getValue(), injected.getValue());
    }

    private static FlowRunTrace.Step step(Recorded r, String nodeId) {
        return r.steps().stream().filter(s -> s.nodeId().equals(nodeId)).findFirst().orElseThrow();
    }

    @Test
    void triggerToAction_recordsOk() {
        deploy("""
                { "nodes": [
                    { "id": "t", "type": "test-trigger", "config": {} },
                    { "id": "a", "type": "action", "config": {} } ],
                  "wires": [ { "from": { "node": "t", "port": 0 }, "to": { "node": "a" } } ] }
                """);

        runFromTrigger();

        Recorded r = recorded();
        assertEquals(SystemLogStatus.OK, r.status());
        assertFalse(r.continuation());
        assertFalse(r.injected());
        assertEquals(2, r.steps().size());
        assertEquals("port:0", step(r, "t").outcome());
    }

    @Test
    void conditionOnUnconnectedPort_recordsStopped() {
        deploy("""
                { "nodes": [
                    { "id": "t", "type": "test-trigger", "config": {} },
                    { "id": "c", "type": "cond", "name": "Nur nachts", "config": { "port": 1 } },
                    { "id": "a", "type": "action", "config": {} } ],
                  "wires": [
                    { "from": { "node": "t", "port": 0 }, "to": { "node": "c" } },
                    { "from": { "node": "c", "port": 0 }, "to": { "node": "a" } } ] }
                """);

        runFromTrigger();

        Recorded r = recorded();
        assertEquals(SystemLogStatus.STOPPED, r.status());
        assertEquals("stopped", step(r, "c").outcome());
        assertEquals("Nur nachts", step(r, "c").label());
    }

    @Test
    void conditionOnConnectedPort1_isOk() {
        deploy("""
                { "nodes": [
                    { "id": "t", "type": "test-trigger", "config": {} },
                    { "id": "c", "type": "cond", "config": { "port": 1 } },
                    { "id": "a", "type": "action", "config": {} } ],
                  "wires": [
                    { "from": { "node": "t", "port": 0 }, "to": { "node": "c" } },
                    { "from": { "node": "c", "port": 1 }, "to": { "node": "a" } } ] }
                """);

        runFromTrigger();

        Recorded r = recorded();
        assertEquals(SystemLogStatus.OK, r.status());
        assertEquals("port:1", step(r, "c").outcome());
    }

    @Test
    void gateReturningNone_recordsRateLimited() {
        deploy("""
                { "nodes": [
                    { "id": "t", "type": "test-trigger", "config": {} },
                    { "id": "g", "type": "gate", "config": {} },
                    { "id": "a", "type": "action", "config": {} } ],
                  "wires": [
                    { "from": { "node": "t", "port": 0 }, "to": { "node": "g" } },
                    { "from": { "node": "g", "port": 0 }, "to": { "node": "a" } } ] }
                """);

        runFromTrigger();

        Recorded r = recorded();
        assertEquals(SystemLogStatus.RATE_LIMITED, r.status());
        assertEquals("dropped", step(r, "g").outcome());
    }

    @Test
    void deferringReturningNone_recordsWaiting() {
        deploy("""
                { "nodes": [
                    { "id": "t", "type": "test-trigger", "config": {} },
                    { "id": "d", "type": "defer", "config": {} },
                    { "id": "a", "type": "action", "config": {} } ],
                  "wires": [
                    { "from": { "node": "t", "port": 0 }, "to": { "node": "d" } },
                    { "from": { "node": "d", "port": 0 }, "to": { "node": "a" } } ] }
                """);

        runFromTrigger();

        Recorded r = recorded();
        assertEquals(SystemLogStatus.WAITING, r.status());
        assertEquals("deferred", step(r, "d").outcome());
    }

    @Test
    void throwingNode_recordsError_andOtherBranchStillRuns() {
        deploy("""
                { "nodes": [
                    { "id": "t", "type": "test-trigger", "config": {} },
                    { "id": "bad", "type": "failing", "config": {} },
                    { "id": "a", "type": "action", "config": {} } ],
                  "wires": [
                    { "from": { "node": "t", "port": 0 }, "to": { "node": "bad" } },
                    { "from": { "node": "t", "port": 0 }, "to": { "node": "a" } } ] }
                """);

        runFromTrigger();

        Recorded r = recorded();
        assertEquals(SystemLogStatus.ERROR, r.status());
        assertEquals("failed", step(r, "bad").outcome());
        assertEquals("boom", step(r, "bad").error());
        assertEquals(List.of("a"), received);
    }

    @Test
    void reportFailure_recordsErrorWithoutAbortingBranch() {
        deploy("""
                { "nodes": [
                    { "id": "t", "type": "test-trigger", "config": {} },
                    { "id": "rep", "type": "reporting", "config": {} },
                    { "id": "a", "type": "action", "config": {} } ],
                  "wires": [
                    { "from": { "node": "t", "port": 0 }, "to": { "node": "rep" } },
                    { "from": { "node": "rep", "port": 0 }, "to": { "node": "a" } } ] }
                """);

        runFromTrigger();

        Recorded r = recorded();
        assertEquals(SystemLogStatus.ERROR, r.status());
        assertEquals("reported-failure", step(r, "rep").outcome());
        assertEquals("Telegram 502", step(r, "rep").error());
        assertEquals(List.of("a"), received);
    }

    @Test
    void errorBeatsStopped() {
        deploy("""
                { "nodes": [
                    { "id": "t", "type": "test-trigger", "config": {} },
                    { "id": "c", "type": "cond", "config": { "port": 1 } },
                    { "id": "bad", "type": "failing", "config": {} } ],
                  "wires": [
                    { "from": { "node": "t", "port": 0 }, "to": { "node": "c" } },
                    { "from": { "node": "t", "port": 0 }, "to": { "node": "bad" } } ] }
                """);

        runFromTrigger();

        assertEquals(SystemLogStatus.ERROR, recorded().status());
    }

    @Test
    void continuationKeepsRunId() {
        deploy("""
                { "nodes": [
                    { "id": "t", "type": "test-trigger", "config": {} },
                    { "id": "d", "type": "defer", "config": {} },
                    { "id": "a", "type": "action", "config": {} } ],
                  "wires": [
                    { "from": { "node": "t", "port": 0 }, "to": { "node": "d" } },
                    { "from": { "node": "d", "port": 0 }, "to": { "node": "a" } } ] }
                """);

        engine.runFrom(1L, "d", 0, FlowMessage.of(Map.of(FlowRunTrace.RUN_ID_KEY, "abc")));

        verify(runRecorder).record(eq(1L), eq("abc"), eq(SystemLogStatus.OK), anyList(), eq(true), eq(false));
        assertEquals(List.<Object>of("abc"), seenRunIds);
    }

    @Test
    void runIdIsPassedDownstream() {
        deploy("""
                { "nodes": [
                    { "id": "t", "type": "test-trigger", "config": {} },
                    { "id": "a", "type": "action", "config": {} } ],
                  "wires": [ { "from": { "node": "t", "port": 0 }, "to": { "node": "a" } } ] }
                """);

        runFromTrigger();

        Recorded r = recorded();
        assertEquals(List.<Object>of(r.runId()), seenRunIds);
        assertEquals(36, r.runId().length());
    }

    @Test
    void injectedMessage_isMarkedAsInjected() {
        deploy("""
                { "nodes": [
                    { "id": "t", "type": "test-trigger", "config": {} },
                    { "id": "a", "type": "action", "config": {} } ],
                  "wires": [ { "from": { "node": "t", "port": 0 }, "to": { "node": "a" } } ] }
                """);

        engine.runFrom(1L, "t", 0, FlowMessage.of(Map.of(FlowRunTrace.INJECTED_KEY, true)));

        assertTrue(recorded().injected());
    }

    @Test
    void hopLimit_recordsError() {
        deploy("""
                { "nodes": [
                    { "id": "t", "type": "test-trigger", "config": {} },
                    { "id": "a", "type": "action", "config": {} },
                    { "id": "b", "type": "action", "config": {} } ],
                  "wires": [
                    { "from": { "node": "t", "port": 0 }, "to": { "node": "a" } },
                    { "from": { "node": "a", "port": 0 }, "to": { "node": "b" } },
                    { "from": { "node": "b", "port": 0 }, "to": { "node": "a" } } ] }
                """);

        runFromTrigger();

        Recorded r = recorded();
        assertEquals(SystemLogStatus.ERROR, r.status());
        FlowRunTrace.Step last = r.steps().get(r.steps().size() - 1);
        assertEquals("failed", last.outcome());
        assertEquals("Hop-Limit erreicht", last.error());
        assertEquals(100, received.size());
    }

    @Test
    void recorderThrowing_doesNotBreakRun() {
        doThrow(new RuntimeException("db down")).when(runRecorder)
                .record(anyLong(), anyString(), any(), anyList(), anyBoolean(), anyBoolean());
        deploy("""
                { "nodes": [
                    { "id": "t", "type": "test-trigger", "config": {} },
                    { "id": "a", "type": "action", "config": {} } ],
                  "wires": [ { "from": { "node": "t", "port": 0 }, "to": { "node": "a" } } ] }
                """);

        runFromTrigger();

        assertEquals(List.of("a"), received);
        assertTrue(FlowRunTrace.currentRunId().isEmpty());
    }

    @Test
    void nestedSynchronousRun_restoresOuterTrace() {
        deploy("""
                { "nodes": [
                    { "id": "t", "type": "test-trigger", "config": {} },
                    { "id": "er", "type": "emit-report", "config": {} },
                    { "id": "a", "type": "action", "config": {} } ],
                  "wires": [
                    { "from": { "node": "t", "port": 0 }, "to": { "node": "er" } },
                    { "from": { "node": "er", "port": 0 }, "to": { "node": "a" } } ] }
                """);

        runFromTrigger();

        ArgumentCaptor<SystemLogStatus> status = ArgumentCaptor.forClass(SystemLogStatus.class);
        verify(runRecorder, org.mockito.Mockito.times(2)).record(eq(1L), anyString(), status.capture(), anyList(),
                anyBoolean(), anyBoolean());
        // Erst endet die innere Fortsetzung (OK), danach der aeussere Lauf mit dem gemeldeten Fehler.
        assertEquals(List.of(SystemLogStatus.OK, SystemLogStatus.ERROR), status.getAllValues());
        assertTrue(FlowRunTrace.currentRunId().isEmpty());
    }

    @Test
    void traceIsClearedAfterRun() {
        deploy("""
                { "nodes": [
                    { "id": "t", "type": "test-trigger", "config": {} },
                    { "id": "bad", "type": "failing", "config": {} } ],
                  "wires": [ { "from": { "node": "t", "port": 0 }, "to": { "node": "bad" } } ] }
                """);

        runFromTrigger();

        assertTrue(FlowRunTrace.currentRunId().isEmpty());
    }
}
