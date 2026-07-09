package com.household.manager.flowengine.nodes;

import com.household.manager.flowengine.FlowMessage;
import com.household.manager.flowengine.NodeContext;
import com.household.manager.flowengine.NodeResult;
import com.household.manager.flowengine.model.NodeConfig;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import static org.junit.jupiter.api.Assertions.*;

class DebugNodeHandlerTest {

    @Test
    void writesToDebugAndHasNoOutputs() {
        StringBuilder debugged = new StringBuilder();
        NodeContext ctx = new NodeContext() {
            public long flowId() { return 1L; }
            public String nodeId() { return "d"; }
            public ConcurrentMap<String, Object> state() { return new ConcurrentHashMap<>(); }
            public void emit(int port, FlowMessage message) { }
            public org.springframework.scheduling.TaskScheduler scheduler() { return null; }
            public void debug(String label, FlowMessage message) { debugged.append(label).append("|").append(message.get("v")); }
        };
        DebugNodeHandler handler = new DebugNodeHandler();

        NodeResult result = handler.handle(FlowMessage.of(Map.of("v", "42")),
                new NodeConfig(Map.of("label", "hier")), ctx);

        assertEquals("hier|42", debugged.toString());
        assertTrue(result.outputs().isEmpty());
        assertEquals(0, handler.outputPorts());
        assertTrue(handler.validate(NodeConfig.empty()).isEmpty());
    }
}
