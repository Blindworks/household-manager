package com.household.manager.flowengine;

import com.household.manager.repository.FlowRepository;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FlowTriggerRecorderTest {

    private final FlowRepository flowRepository = mock(FlowRepository.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-09-24T17:30:00Z"), ZoneId.of("Europe/Berlin"));
    private final FlowTriggerRecorder recorder = new FlowTriggerRecorder(flowRepository, clock);

    @Test
    void speichertDenZeitpunktInHaushaltszeit() {
        recorder.recordTriggered(4L);

        verify(flowRepository).updateLastTriggeredAt(4L, LocalDateTime.of(2026, 9, 24, 19, 30));
    }

    @Test
    void einDbFehlerBrichtDenFlowLaufNichtAb() {
        when(flowRepository.updateLastTriggeredAt(anyLong(), any())).thenThrow(new IllegalStateException("db down"));

        assertDoesNotThrow(() -> recorder.recordTriggered(4L));
    }
}
