package com.household.manager.charging;

import com.household.manager.model.entity.ChargingPointOccupancy;
import com.household.manager.repository.ChargingPointOccupancyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
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

    private ChargingOccupancyTracker tracker;

    @BeforeEach
    void setUp() {
        tracker = new ChargingOccupancyTracker(repository, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static ChargePoint point(String id, ChargePointStatus status) {
        return new ChargePoint(id, status, 150.0, "CCS", null);
    }

    private static ChargePoint point(String id, ChargePointStatus status, Instant statusSince) {
        return new ChargePoint(id, status, 150.0, "CCS", statusSince);
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
}
