package com.household.manager.flowengine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.household.manager.model.entity.Flow;
import com.household.manager.repository.FlowRepository;
import com.household.manager.systemlog.SystemLogEvent;
import com.household.manager.systemlog.SystemLogKind;
import com.household.manager.systemlog.SystemLogStatus;
import com.household.manager.systemlog.SystemLogWriter;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FlowRunRecorderTest {

    private final SystemLogWriter writer = mock(SystemLogWriter.class);
    private final FlowRepository flowRepository = mock(FlowRepository.class);
    private final FlowRunRecorder recorder = new FlowRunRecorder(writer, flowRepository, new ObjectMapper(),
            Clock.fixed(Instant.parse("2026-10-22T10:00:00Z"), ZoneId.of("Europe/Berlin")));

    private static final FlowRunTrace.Step TRIGGER =
            new FlowRunTrace.Step("t", "entity-state-trigger", "Tür auf", "port:0", null);

    private SystemLogEvent written() {
        ArgumentCaptor<SystemLogEvent> event = ArgumentCaptor.forClass(SystemLogEvent.class);
        verify(writer).write(event.capture());
        return event.getValue();
    }

    @Test
    void stoppedRun_namesTheConditionInTheMessage() {
        when(flowRepository.findById(5L)).thenReturn(Optional.of(Flow.builder().id(5L).name("Licht an").build()));
        FlowRunTrace.Step condition = new FlowRunTrace.Step("c", "time-condition", "Nur nachts", "stopped", null);

        recorder.record(5L, "run-1", SystemLogStatus.STOPPED, List.of(TRIGGER, condition), false, false);

        SystemLogEvent event = written();
        assertEquals(SystemLogKind.FLOW_RUN, event.kind());
        assertEquals(SystemLogStatus.STOPPED, event.status());
        assertEquals("Licht an", event.title());
        assertEquals("5", event.source());
        assertEquals(5L, event.flowId());
        assertEquals("run-1", event.runId());
        assertEquals("gestoppt an ‚Nur nachts‘", event.message());
        assertTrue(event.detailsJson().contains("\"outcome\":\"stopped\""));
    }

    @Test
    void errorRun_carriesTheErrorText_andFallsBackToNodeId() {
        when(flowRepository.findById(5L)).thenReturn(Optional.empty());
        FlowRunTrace.Step failed = new FlowRunTrace.Step("send", "telegram-send", null, "reported-failure",
                "Telegram 502");

        recorder.record(5L, "run-1", SystemLogStatus.ERROR, List.of(TRIGGER, failed), true, true);

        SystemLogEvent event = written();
        assertEquals("Flow 5 (Test)", event.title());
        assertEquals("Fortsetzung: Fehler an ‚send‘: Telegram 502", event.message());
    }

    @Test
    void flowNameIsReadOnceAndCached_untilForgotten() {
        when(flowRepository.findById(5L)).thenReturn(Optional.of(Flow.builder().id(5L).name("A").build()));

        recorder.record(5L, "r1", SystemLogStatus.OK, List.of(TRIGGER), false, false);
        recorder.record(5L, "r2", SystemLogStatus.OK, List.of(TRIGGER), false, false);
        verify(flowRepository, times(1)).findById(5L);

        recorder.forgetName(5L);
        recorder.record(5L, "r3", SystemLogStatus.OK, List.of(TRIGGER), false, false);
        verify(flowRepository, times(2)).findById(5L);
    }

    @Test
    void repositoryFailure_neverThrows_andUsesFallbackTitle() {
        when(flowRepository.findById(5L)).thenThrow(new RuntimeException("db down"));

        recorder.record(5L, "r1", SystemLogStatus.OK, List.of(TRIGGER), false, false);

        SystemLogEvent event = written();
        assertEquals("Flow 5", event.title());
        assertEquals("ausgeführt (1 Node)", event.message());
    }
}
