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
import org.springframework.scheduling.support.SimpleTriggerContext;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

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

    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");

    private final ScheduleTriggerHandler handler = new ScheduleTriggerHandler(Clock.system(BERLIN));
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
    void cronIsEvaluatedInHouseholdZoneNotInJvmZone() {
        // 06:00 Berliner Sommerzeit = 04:00 UTC. Die Gegenprobe mit einer UTC-Clock
        // stellt sicher, dass die Zone wirklich aus dem Clock-Bean kommt: wuerde der
        // Trigger auf die JVM-Zone zurueckfallen, koennte nur einer der beiden Faelle stimmen.
        Instant summerMidnightUtc = Instant.parse("2026-07-01T00:00:00Z");

        assertEquals(Instant.parse("2026-07-01T04:00:00Z"),
                nextExecution(new ScheduleTriggerHandler(Clock.system(BERLIN)), "0 0 6 * * *", summerMidnightUtc));
        assertEquals(Instant.parse("2026-07-01T06:00:00Z"),
                nextExecution(new ScheduleTriggerHandler(Clock.systemUTC()), "0 0 6 * * *", summerMidnightUtc));
    }

    @Test
    void cronFollowsDaylightSavingTime() {
        // 06:00 Berliner Winterzeit = 05:00 UTC
        assertEquals(Instant.parse("2026-12-01T05:00:00Z"),
                nextExecution(handler, "0 0 6 * * *", Instant.parse("2026-12-01T00:00:00Z")));
    }

    private Instant nextExecution(ScheduleTriggerHandler subject, String cron, Instant now) {
        ArgumentCaptor<CronTrigger> trigger = ArgumentCaptor.forClass(CronTrigger.class);
        doReturn(mock(ScheduledFuture.class)).when(taskScheduler).schedule(any(Runnable.class), trigger.capture());

        subject.register(new NodeConfig(Map.of("cron", cron)), ctx);

        return trigger.getValue().nextExecution(new SimpleTriggerContext(Clock.fixed(now, ZoneId.of("UTC"))));
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
