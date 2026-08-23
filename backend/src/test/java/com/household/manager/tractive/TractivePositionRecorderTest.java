package com.household.manager.tractive;

import com.household.manager.model.entity.TractivePosition;
import com.household.manager.repository.TractivePositionRepository;
import com.household.manager.tractive.dto.TractivePositionDto;
import com.household.manager.tractive.dto.TractiveTrackableDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TractivePositionRecorderTest {

    private static final long REPORT_EPOCH = 1_800_000_000L;

    @Mock
    private TractivePositionRepository repository;

    private TractivePositionRecorder recorder;

    @BeforeEach
    void setUp() {
        recorder = new TractivePositionRecorder(repository);
    }

    private TractivePetSnapshot snapshot(TractivePositionDto position) {
        TractiveTrackableDto trackable = new TractiveTrackableDto(
                "obj-1", "dev-9", new TractiveTrackableDto.Details("Toni", "dog"));
        return new TractivePetSnapshot(trackable, position, null, List.of());
    }

    private TractivePositionDto position(long epochSeconds) {
        return new TractivePositionDto(List.of(48.2182, 16.3738), 12.0, "GPS", epochSeconds);
    }

    @Test
    void speichertEinenNeuenPositionsbericht() {
        when(repository.existsByTrackerIdAndPositionTime(anyString(), any(Instant.class)))
                .thenReturn(false);

        recorder.record(List.of(snapshot(position(REPORT_EPOCH))));

        ArgumentCaptor<TractivePosition> captor = ArgumentCaptor.forClass(TractivePosition.class);
        verify(repository).save(captor.capture());
        TractivePosition saved = captor.getValue();
        assertEquals("dev-9", saved.getTrackerId());
        assertEquals(Instant.ofEpochSecond(REPORT_EPOCH), saved.getPositionTime());
        assertEquals(48.2182, saved.getLatitude());
        assertEquals(16.3738, saved.getLongitude());
        assertEquals(12.0, saved.getAccuracy());
        assertEquals("GPS", saved.getSensorUsed());
    }

    @Test
    void schreibtKeineZweiteZeileFuerDenselbenBericht() {
        // Bei ausgeschaltetem Tracker liefert die API denselben Zeitstempel immer
        // wieder. Wuerde er jedes Mal gespeichert, entstuende ein kuenstlich
        // lueckenloser Strom und der Detektor saehe einen nie endenden Spaziergang.
        when(repository.existsByTrackerIdAndPositionTime("dev-9", Instant.ofEpochSecond(REPORT_EPOCH)))
                .thenReturn(true);

        recorder.record(List.of(snapshot(position(REPORT_EPOCH))));

        verify(repository, never()).save(any());
    }

    @Test
    void ueberspringtSnapshotOhnePosition() {
        recorder.record(List.of(snapshot(null)));

        verifyNoInteractions(repository);
    }

    @Test
    void ueberspringtPositionOhneKoordinaten() {
        recorder.record(List.of(snapshot(
                new TractivePositionDto(null, null, "GPS", REPORT_EPOCH))));

        verifyNoInteractions(repository);
    }

    @Test
    void ueberspringtPositionOhneZeitstempel() {
        // Ein geratener Zeitstempel wuerde die Luecken verfaelschen, an denen der
        // Detektor die Runden trennt.
        recorder.record(List.of(snapshot(
                new TractivePositionDto(List.of(48.2182, 16.3738), null, "GPS", null))));

        verifyNoInteractions(repository);
    }

    @Test
    void einRepositoryFehlerBrichtDenPollNichtAb() {
        when(repository.existsByTrackerIdAndPositionTime(anyString(), any(Instant.class)))
                .thenThrow(new RuntimeException("DB weg"));

        assertDoesNotThrow(() -> recorder.record(List.of(snapshot(position(REPORT_EPOCH)))));
    }

    @Test
    void einKaputtesTierStopptDieAnderenNicht() {
        TractiveTrackableDto zweiter = new TractiveTrackableDto(
                "obj-2", "dev-8", new TractiveTrackableDto.Details("Rex", "dog"));
        when(repository.existsByTrackerIdAndPositionTime(eq("dev-9"), any(Instant.class)))
                .thenThrow(new RuntimeException("DB weg"));
        when(repository.existsByTrackerIdAndPositionTime(eq("dev-8"), any(Instant.class)))
                .thenReturn(false);

        recorder.record(List.of(
                snapshot(position(REPORT_EPOCH)),
                new TractivePetSnapshot(zweiter, position(REPORT_EPOCH), null, List.of())));

        verify(repository, times(1)).save(any());
    }
}
