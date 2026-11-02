package com.household.manager.charging.forecast;

import com.household.manager.model.entity.ChargingObservation;
import com.household.manager.model.entity.ChargingOccupancyRecord;
import com.household.manager.repository.ChargingObservationRepository;
import com.household.manager.repository.ChargingOccupancyHistoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChargingHistoryRecorderTest {

    @Mock
    private ChargingOccupancyHistoryRepository history;
    @Mock
    private ChargingObservationRepository observations;

    private ChargingHistoryRecorder recorder;

    @BeforeEach
    void setUp() {
        recorder = new ChargingHistoryRecorder(history, observations);
    }

    @Test
    void recordObservationAddiertInDenStundenBucket() {
        Instant hour = Instant.parse("2026-09-19T10:00:00Z");
        ChargingObservation[] stored = new ChargingObservation[1];
        when(observations.findByStationIdAndHourStart("S1", hour))
                .thenAnswer(inv -> Optional.ofNullable(stored[0]));
        when(observations.save(any(ChargingObservation.class))).thenAnswer(inv -> {
            stored[0] = inv.getArgument(0);
            return stored[0];
        });

        recorder.recordObservation("S1", Instant.parse("2026-09-19T10:05:00Z"), 60, 1, 1);
        recorder.recordObservation("S1", Instant.parse("2026-09-19T10:06:00Z"), 60, 1, 1);

        verify(observations, times(2)).save(any(ChargingObservation.class));
        assertThat(stored[0].getHourStart()).isEqualTo(hour);
        assertThat(stored[0].getObservedSeconds()).isEqualTo(120);
        assertThat(stored[0].getFreePointSeconds()).isEqualTo(120);
        assertThat(stored[0].getOccupiedPointSeconds()).isEqualTo(120);
    }

    @Test
    void recordEndedSpeichertAufSekundenAbgeschnitten() {
        recorder.recordEnded("S1", "P1",
                Instant.parse("2026-09-19T10:00:00.900Z"), Instant.parse("2026-09-19T10:30:00.400Z"));

        ArgumentCaptor<ChargingOccupancyRecord> captor = ArgumentCaptor.forClass(ChargingOccupancyRecord.class);
        verify(history).save(captor.capture());
        assertThat(captor.getValue().getStartedAt()).isEqualTo(Instant.parse("2026-09-19T10:00:00Z"));
        assertThat(captor.getValue().getEndedAt()).isEqualTo(Instant.parse("2026-09-19T10:30:00Z"));
    }

    @Test
    void recordEndedVerwirftNichtPositiveDauer() {
        Instant t = Instant.parse("2026-09-19T10:00:00Z");

        recorder.recordEnded("S1", "P1", t, t);
        recorder.recordEnded("S1", "P1", t, t.minusSeconds(5));

        verify(history, never()).save(any());
    }

    @Test
    void recorderWirftNieBeiRepositoryFehler() {
        doThrow(new RuntimeException("db")).when(history).save(any());
        when(observations.findByStationIdAndHourStart(any(), any())).thenThrow(new RuntimeException("db"));
        Instant t = Instant.parse("2026-09-19T10:00:00Z");

        assertThatCode(() -> {
            recorder.recordEnded("S1", "P1", t, t.plusSeconds(600));
            recorder.recordObservation("S1", t, 60, 1, 1);
        }).doesNotThrowAnyException();
    }
}
