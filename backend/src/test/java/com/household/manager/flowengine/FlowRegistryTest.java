package com.household.manager.flowengine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.household.manager.flowengine.model.FlowDefinitionParser;
import com.household.manager.flowengine.model.NodeConfig;
import com.household.manager.systemlog.FlowPendingRunTracker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

class FlowRegistryTest {

    /** Trigger, dessen register()-Cleanup das Test-Runnable ist. */
    private static class CleanupTrigger implements TriggerNodeHandler {
        final Runnable cleanup = mock(Runnable.class);
        public String type() { return "test-trigger"; }
        public int outputPorts() { return 1; }
        public List<String> validate(NodeConfig config) { return List.of(); }
        public Optional<String> watchedEntityId(NodeConfig config) { return Optional.of("sensor.x"); }
        public Runnable register(NodeConfig config, NodeContext ctx) { return cleanup; }
    }

    private static final String DEFINITION = """
            { "nodes": [ { "id": "t", "type": "test-trigger", "config": {} } ], "wires": [] }
            """;

    private final FlowPendingRunTracker tracker = mock(FlowPendingRunTracker.class);
    private final CleanupTrigger trigger = new CleanupTrigger();
    private final FlowDefinitionParser parser = new FlowDefinitionParser(new ObjectMapper());
    private FlowRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new FlowRegistry(List.of(trigger), tracker);
        FlowEngine engine = mock(FlowEngine.class);
        when(engine.debugBuffer()).thenReturn(new DebugBuffer());
        registry.setEngine(engine);
    }

    @Test
    void redeploy_abortsBeforeCleanups() {
        registry.deploy(3L, parser.parse(DEFINITION));

        registry.deploy(3L, parser.parse(DEFINITION));

        InOrder order = inOrder(tracker, trigger.cleanup);
        order.verify(tracker).abortAll(3L, "durch Re-Deploy abgebrochen");
        order.verify(trigger.cleanup).run();
    }

    @Test
    void undeploy_abortsWithDeactivatedReason() {
        registry.deploy(3L, parser.parse(DEFINITION));

        registry.undeploy(3L);

        InOrder order = inOrder(tracker, trigger.cleanup);
        order.verify(tracker).abortAll(3L, "Flow deaktiviert oder gelöscht");
        order.verify(trigger.cleanup).run();
    }

    @Test
    void firstDeploy_doesNotAbort() {
        registry.deploy(3L, parser.parse(DEFINITION));

        verify(tracker, never()).abortAll(anyLong(), any());
    }

    @Test
    void undeployOfUnknownFlow_doesNotAbort() {
        registry.undeploy(42L);

        verify(tracker, never()).abortAll(anyLong(), any());
    }
}
