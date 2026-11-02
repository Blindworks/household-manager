package com.household.manager.charging;

import com.household.manager.charging.forecast.ChargingHistoryRecorder;
import com.household.manager.model.entity.ChargingPointOccupancy;
import com.household.manager.repository.ChargingPointOccupancyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.ZoneId;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChargingOccupancyTrackerTest {

    private static final Instant NOW = Instant.parse("2026-09-19T10:00:00Z");
    private static final Instant EARLIER = Instant.parse("2026-09-19T09:20:00Z");

    @Mock
    private ChargingPointOccupancyRepository repository;

    @Mock
    private ChargingHistoryRecorder recorder;

    private Instant current;
    private ChargingOccupancyTracker tracker;

    @BeforeEach
    void setUp() {
        current = NOW;
        Clock movable = new Clock() {
            @Override
            public ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return current;
            }
        };
        tracker = new ChargingOccupancyTracker(repository, movable, recorder);
    }

    private static ChargingPointOccupancy rowSince(Instant since) {
        return ChargingPointOccupancy.builder()
                .chargePointId("P1").stationId("S1").occupiedSince(since).firstSeenOccupiedAt(since).build();
    }

    private static ChargePoint point(String id, ChargePointStatus status) {
        return new ChargePoint(id, status, 150.0, "CCS", null, null);
    }

    private static ChargePoint point(String id, ChargePointStatus status, Instant statusSince) {
        return new ChargePoint(id, status, 150.0, "CCS", statusSince, null);
    }

    private static ChargingStationDetails details(ChargePointStatus status) {
        return new ChargingStationDetails("S1", List.of(point("P1", status)));
    }

    private static ChargingStationDetails details(ChargePointStatus status, Instant statusSince) {
        return new ChargingStationDetails("S1", List.of(point("P1", status, statusSince)));
    }

    private static ChargingPointOccupancy existingRow() {
        return ChargingPointOccupancy.builder()
                .chargePointId("P1").stationId("S1").occupiedSince(EARLIER).firstSeenOccupiedAt(EARLIER).build();
    }

    @Test
    void erstsichtungBelegtSetztNurFirstSeenOhneBeginn() {
        when(repository.findByStationId("S1")).thenReturn(List.of());

        tracker.record(details(ChargePointStatus.OCCUPIED));

        ArgumentCaptor<ChargingPointOccupancy> captor = ArgumentCaptor.forClass(ChargingPointOccupancy.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getOccupiedSince()).isNull();
        assertThat(captor.getValue().getFirstSeenOccupiedAt()).isEqualTo(NOW);
    }

    @Test
    void uebergangFreiNachBelegtSetztDenBeginn() {
        when(repository.findByStationId("S1")).thenReturn(List.of());
        tracker.record(details(ChargePointStatus.FREE));

        tracker.record(details(ChargePointStatus.OCCUPIED));

        ArgumentCaptor<ChargingPointOccupancy> captor = ArgumentCaptor.forClass(ChargingPointOccupancy.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getOccupiedSince()).isEqualTo(NOW);
    }

    @Test
    void belegtNachFreiLoeschtDieZeile() {
        ChargingPointOccupancy existing = existingRow();
        when(repository.findByStationId("S1")).thenReturn(List.of(existing));

        tracker.record(details(ChargePointStatus.FREE));

        verify(repository).delete(existing);
        verify(repository, never()).save(any());
    }

    @Test
    void weiterhinBelegtLaesstDieZeileUnberuehrt() {
        when(repository.findByStationId("S1")).thenReturn(List.of(existingRow()));

        tracker.record(details(ChargePointStatus.OCCUPIED));

        verify(repository, never()).save(any());
        verify(repository, never()).delete(any());
    }

    @Test
    void unknownAendertNichtsAnEinerBestehendenZeile() {
        when(repository.findByStationId("S1")).thenReturn(List.of(existingRow()));

        tracker.record(details(ChargePointStatus.UNKNOWN));

        verify(repository, never()).save(any());
        verify(repository, never()).delete(any());
    }

    @Test
    void ausserBetriebLoeschtDieZeile() {
        ChargingPointOccupancy existing = existingRow();
        when(repository.findByStationId("S1")).thenReturn(List.of(existing));

        tracker.record(details(ChargePointStatus.OUT_OF_SERVICE));

        verify(repository).delete(existing);
    }

    @Test
    void beginnKommtAusStatusSinceDerQuelle() {
        when(repository.findByStationId("S1")).thenReturn(List.of());
        Instant statusSince = Instant.parse("2026-09-19T08:00:00Z");

        tracker.record(details(ChargePointStatus.OCCUPIED, statusSince));

        ArgumentCaptor<ChargingPointOccupancy> captor = ArgumentCaptor.forClass(ChargingPointOccupancy.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getOccupiedSince()).isEqualTo(statusSince);
        assertThat(captor.getValue().getFirstSeenOccupiedAt()).isEqualTo(NOW);
    }

    @Test
    void statusSinceFuelltUnbekanntenBeginnNach() {
        ChargingPointOccupancy existing = ChargingPointOccupancy.builder()
                .chargePointId("P1").stationId("S1").occupiedSince(null).firstSeenOccupiedAt(EARLIER).build();
        when(repository.findByStationId("S1")).thenReturn(List.of(existing));
        Instant statusSince = Instant.parse("2026-09-19T08:00:00Z");

        tracker.record(details(ChargePointStatus.OCCUPIED, statusSince));

        ArgumentCaptor<ChargingPointOccupancy> captor = ArgumentCaptor.forClass(ChargingPointOccupancy.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue()).isSameAs(existing);
        assertThat(captor.getValue().getOccupiedSince()).isEqualTo(statusSince);
    }

    @Test
    void forgetStationLoeschtAlleZeilenDerStation() {
        tracker.forgetStation("S1");

        verify(repository).deleteByStationId("S1");
    }

    @Test
    void endeMitStatusSinceWirdAufgezeichnet() {
        Instant start = Instant.parse("2026-09-19T10:00:00Z");
        current = Instant.parse("2026-09-19T10:41:00Z");
        when(repository.findByStationId("S1")).thenReturn(List.of(rowSince(start)));

        tracker.record(details(ChargePointStatus.FREE, Instant.parse("2026-09-19T10:40:00Z")));

        verify(recorder).recordEnded("S1", "P1", start, Instant.parse("2026-09-19T10:40:00Z"));
    }

    @Test
    void endeOhneStatusSinceInnerhalbDerLueckeNimmtPollzeit() {
        Instant start = Instant.parse("2026-09-19T10:00:00Z");
        when(repository.findByStationId("S1")).thenReturn(List.of(rowSince(start)));
        current = Instant.parse("2026-09-19T10:40:00Z");
        tracker.record(details(ChargePointStatus.OCCUPIED));
        current = Instant.parse("2026-09-19T10:41:00Z");

        tracker.record(details(ChargePointStatus.FREE));

        verify(recorder).recordEnded("S1", "P1", start, current);
    }

    @Test
    void nachNeustartOhneStatusSinceWirdVerworfen() {
        current = Instant.parse("2026-09-19T10:41:00Z");
        when(repository.findByStationId("S1"))
                .thenReturn(List.of(rowSince(Instant.parse("2026-09-19T01:00:00Z"))));

        tracker.record(details(ChargePointStatus.FREE));

        verify(recorder, never()).recordEnded(any(), any(), any(), any());
    }

    @Test
    void lueckeUeberZehnMinutenWirdVerworfen() {
        when(repository.findByStationId("S1"))
                .thenReturn(List.of(rowSince(Instant.parse("2026-09-19T10:00:00Z"))));
        current = Instant.parse("2026-09-19T10:20:00Z");
        tracker.record(details(ChargePointStatus.OCCUPIED));
        current = Instant.parse("2026-09-19T10:41:00Z");

        tracker.record(details(ChargePointStatus.FREE));

        verify(recorder, never()).recordEnded(any(), any(), any(), any());
    }

    @Test
    void ausserBetriebWirdNieAufgezeichnet() {
        when(repository.findByStationId("S1")).thenReturn(List.of(rowSince(EARLIER)));
        tracker.record(details(ChargePointStatus.OCCUPIED));

        tracker.record(details(ChargePointStatus.OUT_OF_SERVICE, NOW));

        verify(recorder, never()).recordEnded(any(), any(), any(), any());
    }

    @Test
    void unbekannterBeginnWirdNieAufgezeichnet() {
        ChargingPointOccupancy unknownStart = ChargingPointOccupancy.builder()
                .chargePointId("P1").stationId("S1").occupiedSince(null).firstSeenOccupiedAt(EARLIER).build();
        when(repository.findByStationId("S1")).thenReturn(List.of(unknownStart));
        tracker.record(details(ChargePointStatus.OCCUPIED));

        tracker.record(details(ChargePointStatus.FREE, NOW));

        verify(recorder, never()).recordEnded(any(), any(), any(), any());
    }

    @Test
    void neuesStatusSinceBeiWeiterhinBelegtStartetNeueSitzung() {
        ChargingPointOccupancy existing = rowSince(Instant.parse("2026-09-19T10:00:00Z"));
        when(repository.findByStationId("S1")).thenReturn(List.of(existing));
        current = Instant.parse("2026-09-19T10:41:00Z");
        Instant newSince = Instant.parse("2026-09-19T10:35:00Z");

        tracker.record(details(ChargePointStatus.OCCUPIED, newSince));

        verify(recorder, never()).recordEnded(any(), any(), any(), any());
        ArgumentCaptor<ChargingPointOccupancy> captor = ArgumentCaptor.forClass(ChargingPointOccupancy.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue()).isSameAs(existing);
        assertThat(captor.getValue().getOccupiedSince()).isEqualTo(newSince);
        assertThat(captor.getValue().getFirstSeenOccupiedAt()).isEqualTo(current);
    }

    @Test
    void gleichesStatusSinceBeiWeiterhinBelegtBleibtDieselbeSitzung() {
        Instant start = Instant.parse("2026-09-19T10:00:00Z");
        when(repository.findByStationId("S1")).thenReturn(List.of(rowSince(start)));
        current = Instant.parse("2026-09-19T10:41:00Z");

        tracker.record(details(ChargePointStatus.OCCUPIED, start));

        verify(repository, never()).save(any());
    }

    @Test
    void endeNachLueckeMitStatusSinceSpaeterAlsLueckengrenzeWirdVerworfen() {
        Instant start = Instant.parse("2026-09-19T10:00:00Z");
        when(repository.findByStationId("S1")).thenReturn(List.of(rowSince(start)));
        current = Instant.parse("2026-09-19T10:20:00Z");
        tracker.record(details(ChargePointStatus.OCCUPIED));
        current = Instant.parse("2026-09-19T10:41:00Z");

        tracker.record(details(ChargePointStatus.FREE, Instant.parse("2026-09-19T10:35:00Z")));

        verify(recorder, never()).recordEnded(any(), any(), any(), any());
    }

    @Test
    void endeNachLueckeMitStatusSinceInnerhalbDerLueckengrenzeWirdAufgezeichnet() {
        Instant start = Instant.parse("2026-09-19T10:00:00Z");
        when(repository.findByStationId("S1")).thenReturn(List.of(rowSince(start)));
        current = Instant.parse("2026-09-19T10:20:00Z");
        tracker.record(details(ChargePointStatus.OCCUPIED));
        current = Instant.parse("2026-09-19T10:41:00Z");
        Instant end = Instant.parse("2026-09-19T10:28:00Z");

        tracker.record(details(ChargePointStatus.FREE, end));

        verify(recorder).recordEnded("S1", "P1", start, end);
    }

    @Test
    void endeNachLueckeMitStatusSinceGenauAufDerLueckengrenzeWirdAufgezeichnet() {
        Instant start = Instant.parse("2026-09-19T10:00:00Z");
        when(repository.findByStationId("S1")).thenReturn(List.of(rowSince(start)));
        current = Instant.parse("2026-09-19T10:20:00Z");
        tracker.record(details(ChargePointStatus.OCCUPIED));
        current = Instant.parse("2026-09-19T10:41:00Z");
        Instant end = Instant.parse("2026-09-19T10:30:00Z");

        tracker.record(details(ChargePointStatus.FREE, end));

        verify(recorder).recordEnded("S1", "P1", start, end);
    }

    @Test
    void lueckeVonGenauZehnMinutenWirdNochAufgezeichnet() {
        Instant start = Instant.parse("2026-09-19T09:30:00Z");
        when(repository.findByStationId("S1")).thenReturn(List.of(rowSince(start)));
        current = Instant.parse("2026-09-19T10:00:00Z");
        tracker.record(details(ChargePointStatus.OCCUPIED));
        current = Instant.parse("2026-09-19T10:10:00Z");

        tracker.record(details(ChargePointStatus.FREE));

        verify(recorder).recordEnded("S1", "P1", start, current);
    }
}
