package com.household.manager.charging;

import com.household.manager.model.entity.ChargingFavorite;
import com.household.manager.model.entity.ChargingPointOccupancy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChargingQueryServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-19T10:00:00Z");
    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");

    @Mock
    private ChargingSettingsService settingsService;
    @Mock
    private ChargingFavoriteService favoriteService;
    @Mock
    private ChargingOccupancyTracker tracker;

    private ChargingSnapshot snapshot;
    private ChargingQueryService service;

    @BeforeEach
    void setUp() {
        snapshot = new ChargingSnapshot();
        service = new ChargingQueryService(settingsService, favoriteService, tracker, snapshot,
                Clock.fixed(NOW, BERLIN));
    }

    @Test
    void ohneZuhauseAntwortetConfiguredFalse() {
        when(settingsService.getSettings()).thenReturn(new ChargingSettings(null, null, 10.0, 50.0));

        ChargingDtos.StationsResponse response = service.stations();

        assertThat(response.configured()).isFalse();
        assertThat(response.stations()).isEmpty();
    }

    @Test
    void ohneErfolgreichenPollIstLastPolledAtNull() {
        when(settingsService.getSettings()).thenReturn(new ChargingSettings(50.0, 8.0, 10.0, 50.0));
        when(favoriteService.list()).thenReturn(List.of());

        ChargingDtos.StationsResponse response = service.stations();

        assertThat(response.configured()).isTrue();
        assertThat(response.lastPolledAt()).isNull();
        assertThat(response.stations()).isEmpty();
    }

    @Test
    void favoritenZuerstDannNachEntfernungMitLadepunktenUndDauer() {
        when(settingsService.getSettings()).thenReturn(new ChargingSettings(50.0, 8.0, 10.0, 50.0));
        snapshot.updateArea(List.of(
                new ChargingStation("fern", "Fern", null, null, 50.05, 8.0, 150.0, 2, 2),
                new ChargingStation("nah", "Nah", null, null, 50.01, 8.0, 150.0, 2, 2),
                new ChargingStation("fav", "Lidl", "Lidl", "Hauptstr. 1", 50.08, 8.0, 150.0, 2, 1)), NOW);
        ChargingFavorite favorite = ChargingFavorite.builder().stationId("fav").displayName("Lidl")
                .operator("Lidl").lat(50.08).lon(8.0).createdAt(NOW).build();
        when(favoriteService.list()).thenReturn(List.of(favorite));
        snapshot.updateFavoriteDetails(Map.of("fav", new ChargingStationDetails("fav", List.of(
                new ChargePoint("P1", ChargePointStatus.OCCUPIED, 150.0, "CCS"),
                new ChargePoint("P2", ChargePointStatus.FREE, 150.0, "CCS")))), NOW);
        Instant since = Instant.parse("2026-09-19T09:20:00Z");
        when(tracker.occupancyFor("fav")).thenReturn(Map.of("P1", ChargingPointOccupancy.builder()
                .chargePointId("P1").stationId("fav").occupiedSince(since).firstSeenOccupiedAt(since).build()));

        ChargingDtos.StationsResponse response = service.stations();

        assertThat(response.stations()).extracting(ChargingDtos.StationResponse::stationId)
                .containsExactly("fav", "nah", "fern");
        ChargingDtos.StationResponse fav = response.stations().get(0);
        assertThat(fav.favorite()).isTrue();
        assertThat(fav.chargePoints()).hasSize(2);
        ChargingDtos.ChargePointResponse p1 = fav.chargePoints().get(0);
        assertThat(p1.occupiedSince()).isEqualTo(LocalDateTime.ofInstant(since, BERLIN));
        assertThat(p1.minimumDuration()).isFalse();
        assertThat(response.stations().get(1).chargePoints()).isNull();
        assertThat(response.lastPolledAt()).isEqualTo(LocalDateTime.ofInstant(NOW, BERLIN));
    }

    @Test
    void favoritAusserhalbDesUmkreisesBleibtMitGespeichertenStammdatenInDerListe() {
        when(settingsService.getSettings()).thenReturn(new ChargingSettings(50.0, 8.0, 10.0, 50.0));
        ChargingFavorite favorite = ChargingFavorite.builder().stationId("weit").displayName("Weit weg")
                .operator("Op").lat(51.0).lon(9.0).createdAt(NOW).build();
        when(favoriteService.list()).thenReturn(List.of(favorite));
        snapshot.updateFavoriteDetails(Map.of("weit", new ChargingStationDetails("weit", List.of(
                new ChargePoint("P1", ChargePointStatus.OCCUPIED, null, null)))), NOW);
        when(tracker.occupancyFor("weit")).thenReturn(Map.of("P1", ChargingPointOccupancy.builder()
                .chargePointId("P1").stationId("weit").occupiedSince(null).firstSeenOccupiedAt(NOW).build()));

        ChargingDtos.StationsResponse response = service.stations();

        ChargingDtos.StationResponse weit = response.stations().get(0);
        assertThat(weit.name()).isEqualTo("Weit weg");
        assertThat(weit.total()).isEqualTo(1);
        assertThat(weit.free()).isEqualTo(0);
        assertThat(weit.chargePoints().get(0).minimumDuration()).isTrue();
        assertThat(weit.chargePoints().get(0).occupiedSince()).isEqualTo(LocalDateTime.ofInstant(NOW, BERLIN));
    }
}
