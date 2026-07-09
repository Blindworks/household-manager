package com.household.manager.flowengine.nodes;

import com.household.manager.flowengine.FlowMessage;
import com.household.manager.flowengine.NodeContext;
import com.household.manager.flowengine.model.NodeConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.support.CronTrigger;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ScheduledFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ScheduleTriggerHandlerTest {

    @Mock
    private TaskScheduler taskScheduler;

    private final ScheduleTriggerHandler handler = new ScheduleTriggerHandler();
    private final ConcurrentMap<String, Object> state = new ConcurrentHashMap<>();
    private final List<FlowMessage> emitted = new java.util.ArrayList<>();
    private NodeContext ctx;

    @BeforeEach
    void setUp() {
        ctx = new NodeContext() {
            public long flowId() { return 1L; }
            public String nodeId() { return "s"; }
            public ConcurrentMap<String, Object> state() { return state; }
            public void emit(int port, FlowMessage message) { emitted.add(message); }
            public TaskScheduler scheduler() { return taskScheduler; }
            public void debug(String label, FlowMessage message) { }
        };
    }

    @Test
    void registerSchedulesCronAndCleanupCancels() {
        ScheduledFuture<?> future = mock(ScheduledFuture.class);
        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        doReturn(future).when(taskScheduler).schedule(task.capture(), any(CronTrigger.class));

        Runnable cleanup = handler.register(new NodeConfig(Map.of("cron", "0 0 7 * * *")), ctx);

        task.getValue().run();
        assertEquals(1, emitted.size());
        assertEquals("s", emitted.get(0).get("triggerNodeId"));

        cleanup.run();
        verify(future).cancel(false);
    }

    @Test
    void validateRejectsMissingOrInvalidCron() {
        assertFalse(handler.validate(new NodeConfig(Map.of())).isEmpty());
        assertFalse(handler.validate(new NodeConfig(Map.of("cron", "kaputt"))).isEmpty());
        assertTrue(handler.validate(new NodeConfig(Map.of("cron", "0 0 7 * * *"))).isEmpty());
    }

    @Test
    void watchesNoEntity() {
        assertTrue(handler.watchedEntityId(NodeConfig.empty()).isEmpty());
    }
}
