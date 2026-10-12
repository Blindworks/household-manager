package com.household.manager.agent;

import com.household.manager.model.entity.AgentRun;
import com.household.manager.repository.AgentRunRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AgentRunRecorderTest {

    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");

    private AgentRunRepository repository;
    private AgentRunRecorder recorder;

    @BeforeEach
    void setUp() {
        repository = mock(AgentRunRepository.class);
        Clock clock = Clock.fixed(Instant.parse("2026-10-12T10:00:00Z"), BERLIN);
        recorder = new AgentRunRecorder(repository, clock);
    }

    @Test
    void recordStoresAllFields() {
        LocalDateTime start = LocalDateTime.of(2026, 10, 12, 11, 0);
        AgentRunResult result = new AgentRunResult(true, "Fertig", null, 10, 5, 2, 123);

        recorder.record(new AgentTrigger(AgentTriggerType.WAKE, List.of("a", "b")), result, "m", start);

        ArgumentCaptor<AgentRun> captor = ArgumentCaptor.forClass(AgentRun.class);
        verify(repository).save(captor.capture());
        AgentRun run = captor.getValue();
        assertEquals(AgentTriggerType.WAKE, run.getTriggerType());
        assertEquals("Wecker: a, b", run.getTriggerDetail());
        assertEquals(start, run.getStartedAt());
        assertEquals(123, run.getDurationMs());
        assertEquals("m", run.getModel());
        assertEquals(10, run.getInputTokens());
        assertEquals(5, run.getOutputTokens());
        assertEquals(2, run.getToolCalls());
        assertEquals("Fertig", run.getSummary());
        assertNull(run.getError());
    }

    @Test
    void recordTruncatesSummaryAndError() {
        AgentRunResult result = new AgentRunResult(false, "s".repeat(3000), "e".repeat(1500), 0, 0, 0, 1);

        recorder.record(new AgentTrigger(AgentTriggerType.MANUAL, List.of()), result, "m", LocalDateTime.now());

        ArgumentCaptor<AgentRun> captor = ArgumentCaptor.forClass(AgentRun.class);
        verify(repository).save(captor.capture());
        assertEquals(2000, captor.getValue().getSummary().length());
        assertEquals(1000, captor.getValue().getError().length());
    }

    @Test
    void recordSwallowsRepositoryException() {
        when(repository.save(any())).thenThrow(new RuntimeException("db"));
        AgentRunResult result = new AgentRunResult(true, "x", null, 0, 0, 0, 1);

        assertDoesNotThrow(() -> recorder.record(
                new AgentTrigger(AgentTriggerType.SCHEDULED, List.of()), result, "m", LocalDateTime.now()));
    }

    @Test
    void tokensTodayQueriesFromBerlinMidnight() {
        when(repository.sumTokensSince(any())).thenReturn(Collections.singletonList(new Object[]{100L, 40L}));

        long[] tokens = recorder.tokensToday();

        assertArrayEquals(new long[]{100, 40}, tokens);
        verify(repository).sumTokensSince(LocalDateTime.of(2026, 10, 12, 0, 0));
    }

    @Test
    void tokensTodayHandlesEmptyResult() {
        when(repository.sumTokensSince(any())).thenReturn(List.of());
        assertArrayEquals(new long[]{0, 0}, recorder.tokensToday());
    }

    @Test
    void tokensTodayHandlesNullColumns() {
        when(repository.sumTokensSince(any())).thenReturn(Collections.singletonList(new Object[]{null, null}));
        assertArrayEquals(new long[]{0, 0}, recorder.tokensToday());
    }
}
