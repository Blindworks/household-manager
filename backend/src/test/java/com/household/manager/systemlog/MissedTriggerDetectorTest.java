package com.household.manager.systemlog;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.household.manager.flowengine.FlowRegistry;
import com.household.manager.flowengine.FlowRunRecorder;
import com.household.manager.flowengine.NodeHandler;
import com.household.manager.flowengine.TriggerNodeHandler;
import com.household.manager.flowengine.model.FlowDefinitionParser;
import com.household.manager.flowengine.model.NodeConfig;
import com.household.manager.flowengine.nodes.ScheduleTriggerHandler;
import com.household.manager.model.entity.Flow;
import com.household.manager.repository.FlowRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MissedTriggerDetectorTest {

    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
    // 09:30 Berlin (Winterzeit) am 22.01.2026
    private static final Instant NOW = Instant.parse("2026-01-22T08:30:00Z");

    @Mock private SystemHeartbeat heartbeat;
    @Mock private SystemLogWriter writer;
    @Mock private FlowRepository flowRepository;
    @Mock private FlowRegistry registry;
    @Mock private FlowRunRecorder flowRunRecorder;

    private final Clock clock = Clock.fixed(NOW, BERLIN);
    private MissedTriggerDetector detector;

    @BeforeEach
    void setUp() {
        detector = new MissedTriggerDetector(heartbeat, writer, flowRepository,
                new FlowDefinitionParser(new ObjectMapper()), registry, flowRunRecorder, clock);
        lenient().when(flowRunRecorder.flowName(anyLong())).thenReturn("Morgenlicht");
    }

    private static Flow flow(long id, String triggerType) {
        String json = "{\"nodes\":[{\"id\":\"t1\",\"type\":\"" + triggerType
                + "\",\"config\":{\"cron\":\"0 0 * * * *\"}}],\"wires\":[]}";
        return Flow.builder().id(id).name("F" + id).enabled(true).deployedDefinition(json).build();
    }

    private List<SystemLogEvent> written() {
        ArgumentCaptor<SystemLogEvent> captor = ArgumentCaptor.forClass(SystemLogEvent.class);
        verify(writer, atLeast(0)).write(captor.capture());
        return captor.getAllValues();
    }

    @Test
    void noHeartbeat_writesNothing() {
        when(heartbeat.lastAliveBeforeStart()).thenReturn(Optional.empty());

        detector.detectMissed();

        verifyNoInteractions(writer);
        verifyNoInteractions(flowRepository);
    }

    @Test
    void cronEveryHour_backendDown3h_writesThreeMissed() {
        when(heartbeat.lastAliveBeforeStart()).thenReturn(Optional.of(Instant.parse("2026-01-22T05:30:00Z"))); // 06:30 Berlin
        when(flowRepository.findByEnabledTrueAndDeployedDefinitionNotNull()).thenReturn(List.of(flow(1, "schedule-trigger")));
        when(registry.handler("schedule-trigger")).thenReturn(new ScheduleTriggerHandler(clock));

        detector.detectMissed();

        List<SystemLogEvent> events = written();
        assertEquals(3, events.size());
        assertEquals(LocalDateTime.of(2026, 1, 22, 7, 0), events.get(0).occurredAt());
        assertEquals(LocalDateTime.of(2026, 1, 22, 9, 0), events.get(2).occurredAt());
        SystemLogEvent first = events.get(0);
        assertEquals(SystemLogStatus.MISSED, first.status());
        assertEquals(SystemLogKind.FLOW_RUN, first.kind());
        assertEquals("1", first.source());
        assertEquals(1L, first.flowId());
        assertNull(first.runId());
        assertEquals("Morgenlicht", first.title());
        assertEquals("Backend lief nicht von 22.01.2026 06:30 bis 22.01.2026 09:30", first.message());
    }

    @Test
    void downFor3Weeks_cappedAt7DaysAnd50PerFlow_plusSummary() {
        when(heartbeat.lastAliveBeforeStart()).thenReturn(Optional.of(NOW.minus(Duration.ofDays(21))));
        when(flowRepository.findByEnabledTrueAndDeployedDefinitionNotNull()).thenReturn(List.of(flow(1, "schedule-trigger")));
        when(registry.handler("schedule-trigger")).thenReturn(new ScheduleTriggerHandler(clock));

        detector.detectMissed();

        List<SystemLogEvent> events = written();
        assertEquals(51, events.size());
        SystemLogEvent summary = events.get(50);
        assertEquals("weitere verpasste Termine ausgelassen", summary.message());
        assertEquals(SystemLogStatus.MISSED, summary.status());
        assertEquals("Morgenlicht", summary.title());
        assertEquals(LocalDateTime.of(2026, 1, 22, 9, 30), summary.occurredAt());
        assertEquals(50, events.stream().filter(e -> e.message().startsWith("Backend lief nicht")).count());
        // Fenster beginnt frühestens 7 Tage vor jetzt
        assertTrue(events.get(0).occurredAt().isAfter(LocalDateTime.of(2026, 1, 15, 9, 30)));
    }

    @Test
    void disabledFlow_isIgnored() {
        when(heartbeat.lastAliveBeforeStart()).thenReturn(Optional.of(NOW.minus(Duration.ofHours(3))));
        when(flowRepository.findByEnabledTrueAndDeployedDefinitionNotNull()).thenReturn(List.of());

        detector.detectMissed();

        verifyNoInteractions(writer);
    }

    @Test
    void sunTrigger_usesOccurrencesBetween() {
        when(heartbeat.lastAliveBeforeStart()).thenReturn(Optional.of(NOW.minus(Duration.ofHours(3))));
        when(flowRepository.findByEnabledTrueAndDeployedDefinitionNotNull()).thenReturn(List.of(flow(2, "sun-trigger")));
        TriggerNodeHandler sun = mock(TriggerNodeHandler.class);
        ZonedDateTime sunset = ZonedDateTime.of(2026, 1, 22, 8, 0, 0, 0, BERLIN);
        when(sun.occurrencesBetween(any(NodeConfig.class), any(), any(), eq(51))).thenReturn(List.of(sunset));
        when(registry.handler("sun-trigger")).thenReturn(sun);

        detector.detectMissed();

        List<SystemLogEvent> events = written();
        assertEquals(1, events.size());
        assertEquals(LocalDateTime.of(2026, 1, 22, 8, 0), events.get(0).occurredAt());
    }

    @Test
    void handlerThrows_otherFlowsStillChecked() {
        when(heartbeat.lastAliveBeforeStart()).thenReturn(Optional.of(Instant.parse("2026-01-22T05:30:00Z")));
        when(flowRepository.findByEnabledTrueAndDeployedDefinitionNotNull())
                .thenReturn(List.of(flow(1, "boom-trigger"), flow(2, "schedule-trigger")));
        TriggerNodeHandler boom = mock(TriggerNodeHandler.class);
        when(boom.occurrencesBetween(any(), any(), any(), anyInt())).thenThrow(new IllegalStateException("kaputt"));
        when(registry.handler("boom-trigger")).thenReturn(boom);
        when(registry.handler("schedule-trigger")).thenReturn(new ScheduleTriggerHandler(clock));

        detector.detectMissed();

        assertEquals(3, written().size());
    }

    @Test
    void nonTriggerNodes_areIgnored() {
        when(heartbeat.lastAliveBeforeStart()).thenReturn(Optional.of(NOW.minus(Duration.ofHours(3))));
        when(flowRepository.findByEnabledTrueAndDeployedDefinitionNotNull()).thenReturn(List.of(flow(1, "switch-device")));
        when(registry.handler("switch-device")).thenReturn(mock(NodeHandler.class));

        detector.detectMissed();

        verifyNoInteractions(writer);
    }
}
