package com.household.manager.flowengine.nodes;

import com.household.manager.flowengine.FlowMessage;
import com.household.manager.flowengine.NodeContext;
import com.household.manager.flowengine.NodeResult;
import com.household.manager.flowengine.model.NodeConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.TaskScheduler;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ScheduledFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

@ExtendWith(MockitoExtension.class)
class DelayNodeHandlerTest {

    @Mock
    private TaskScheduler taskScheduler;

    @Test
    void schedulesEmitAndReturnsNoImmediateOutput() {
        List<FlowMessage> emitted = new java.util.ArrayList<>();
        NodeContext ctx = new NodeContext() {
            public long flowId() { return 1L; }
            public String nodeId() { return "d"; }
            public ConcurrentMap<String, Object> state() { return new ConcurrentHashMap<>(); }
            public void emit(int port, FlowMessage message) { emitted.add(message); }
            public TaskScheduler scheduler() { return taskScheduler; }
            public void debug(String label, FlowMessage message) { }
        };
        ScheduledFuture<?> future = mock(ScheduledFuture.class);
        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        doReturn(future).when(taskScheduler).schedule(task.capture(), any(Instant.class));

        DelayNodeHandler handler = new DelayNodeHandler();
        NodeResult result = handler.handle(FlowMessage.of(Map.of("v", "1")),
                new NodeConfig(Map.of("seconds", 300)), ctx);

        assertTrue(result.outputs().isEmpty());
        assertTrue(emitted.isEmpty());
        task.getValue().run();
        assertEquals(1, emitted.size());
    }

    @Test
    void validateRequiresPositiveSeconds() {
        DelayNodeHandler handler = new DelayNodeHandler();
        assertFalse(handler.validate(NodeConfig.empty()).isEmpty());
        assertFalse(handler.validate(new NodeConfig(Map.of("seconds", 0))).isEmpty());
        assertTrue(handler.validate(new NodeConfig(Map.of("seconds", 5))).isEmpty());
    }
}
