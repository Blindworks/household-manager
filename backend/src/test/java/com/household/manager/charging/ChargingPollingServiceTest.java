package com.household.manager.charging;

import com.household.manager.entitystate.EntityStateService;
import com.household.manager.entitystate.EntityStateUpdate;
import com.household.manager.entitystate.mapper.ChargingEntityMapper;
import com.household.manager.model.entity.ChargingFavorite;
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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChargingPollingServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-19T10:00:00Z");

    @Mock
    private ChargingStationSource source;
    @Mock
    private ChargingSettingsService settingsService;
    @Mock
    private ChargingFavoriteService favoriteService;
    @Mock
    private ChargingOccupancyTracker tracker;
    @Mock
    private EntityStateService entityStateService;

    private ChargingProperties properties;
    private ChargingSnapshot snapshot;
    private ChargingPollingService service;

    private static ChargingFavorite favorite(String id) {
        return ChargingFavorite.builder().stationId(id).displayName(id).lat(50).lon(8).createdAt(NOW).build();
    }

    private static ChargingStationDetails details(String id, ChargePointStatus status) {
        return new ChargingStationDetails(id, List.of(new ChargePoint(id + "-1", status, 150.0, "CCS", null, null)));
    }

    @BeforeEach
    void setUp() {
        properties = new ChargingProperties();
        snapshot = new ChargingSnapshot();
        service = new ChargingPollingService(properties, source, settingsService, favoriteService, tracker,
                snapshot, new ChargingEntityMapper(), entityStateService, Clock.fixed(NOW, ZoneOffset.UTC));
        // lenient: nicht jeder Test (z. B. die reinen Favoriten-Tests) fragt settingsService ab.
        org.mockito.Mockito.lenient().when(settingsService.getSettings())
                .thenReturn(new ChargingSettings(50.0, 8.0, 10.0, 50.0));
    }

    @Test
    void umkreisPollFiltertAufKreisUndSchreibtDenSnapshot() {
        when(source.searchArea(any(), anyDouble())).thenReturn(List.of(
                new ChargingStation("nah", "Nah", null, null, 50.01, 8.0, 150.0, 2, 1),
                new ChargingStation("fern", "Fern", null, null, 51.0, 8.0, 150.0, 2, 1)));

        service.pollArea();

        assertThat(snapshot.areaStations()).extracting(ChargingStation::stationId).containsExactly("nah");
        assertThat(snapshot.lastPolledAt()).isEqualTo(NOW);
    }

    @Test
    void umkreisPollOhneZuhauseFragtDieQuelleNicht() {
        when(settingsService.getSettings()).thenReturn(new ChargingSettings(null, null, 10.0, 50.0));

        service.pollArea();

        verify(source, never()).searchArea(any(), anyDouble());
    }

    @Test
    void favoritenPollMeldetEntitaetUndSchreibtBelegung() {
        when(favoriteService.list()).thenReturn(List.of(favorite("S1")));
        when(source.stationDetails("S1")).thenReturn(details("S1", ChargePointStatus.FREE));
        when(tracker.occupancyFor("S1")).thenReturn(Map.of());

        service.pollFavorites();

        verify(tracker).record(details("S1", ChargePointStatus.FREE));
        ArgumentCaptor<EntityStateUpdate> captor = ArgumentCaptor.forClass(EntityStateUpdate.class);
        verify(entityStateService).reportState(captor.capture());
        assertThat(captor.getValue().entityId()).isEqualTo("sensor.charging_s1_free");
        assertThat(captor.getValue().state()).isEqualTo("1");
        assertThat(snapshot.details("S1")).isPresent();
    }

    @Test
    void fehlerBeiEinemFavoritenStoertDieAnderenNichtUndMarkiertNurIhnUnavailable() {
        when(favoriteService.list()).thenReturn(List.of(favorite("S1"), favorite("S2")));
        when(source.stationDetails("S1")).thenThrow(new ChargingSourceException("weg"));
        when(source.stationDetails("S2")).thenReturn(details("S2", ChargePointStatus.OCCUPIED));
        when(tracker.occupancyFor("S2")).thenReturn(Map.of());
        // S1 wurde in einem frueheren Lauf schon gemeldet.
        service.pollFavorites();
        org.mockito.Mockito.reset(entityStateService);
        // doThrow statt when(...).thenThrow(...): ein erneutes when() wuerde den echten Aufruf
        // ausfuehren und damit die zuvor gestubbte Exception erneut werfen (siehe Mockito-Falle).
        org.mockito.Mockito.doThrow(new ChargingSourceException("weg")).when(source).stationDetails("S1");

        service.pollFavorites();

        ArgumentCaptor<EntityStateUpdate> captor = ArgumentCaptor.forClass(EntityStateUpdate.class);
        verify(entityStateService, times(2)).reportState(captor.capture());
        assertThat(captor.getAllValues()).anySatisfy(u -> {
            assertThat(u.entityId()).isEqualTo("sensor.charging_s1_free");
            assertThat(u.state()).isEqualTo("unavailable");
            assertThat(u.attributes()).containsKey("stationName");
        });
        assertThat(captor.getAllValues()).anySatisfy(u -> {
            assertThat(u.entityId()).isEqualTo("sensor.charging_s2_free");
            assertThat(u.state()).isEqualTo("0");
        });
    }

    @Test
    void spaetererFehlschlagBehaeltDieAttributeDesLetztenErfolgs() {
        when(favoriteService.list()).thenReturn(List.of(favorite("S1")));
        when(source.stationDetails("S1")).thenReturn(details("S1", ChargePointStatus.FREE));
        when(tracker.occupancyFor("S1")).thenReturn(Map.of());
        service.pollFavorites();
        org.mockito.Mockito.reset(entityStateService);
        // doThrow statt when(...).thenThrow(...): siehe Mockito-Falle oben.
        org.mockito.Mockito.doThrow(new ChargingSourceException("weg")).when(source).stationDetails("S1");

        service.pollFavorites();

        ArgumentCaptor<EntityStateUpdate> captor = ArgumentCaptor.forClass(EntityStateUpdate.class);
        verify(entityStateService).reportState(captor.capture());
        assertThat(captor.getValue().state()).isEqualTo("unavailable");
        assertThat(captor.getValue().attributes()).containsKey("total");
    }

    @Test
    void entfernterFavoritWirdEinmalUnavailableGemeldet() {
        when(favoriteService.list()).thenReturn(List.of(favorite("S1")));
        when(source.stationDetails("S1")).thenReturn(details("S1", ChargePointStatus.FREE));
        when(tracker.occupancyFor("S1")).thenReturn(Map.of());
        service.pollFavorites();
        org.mockito.Mockito.reset(entityStateService);
        when(favoriteService.list()).thenReturn(List.of());

        service.pollFavorites();

        ArgumentCaptor<EntityStateUpdate> captor = ArgumentCaptor.forClass(EntityStateUpdate.class);
        verify(entityStateService).reportState(captor.capture());
        assertThat(captor.getValue().entityId()).isEqualTo("sensor.charging_s1_free");
        assertThat(captor.getValue().state()).isEqualTo("unavailable");
    }

    @Test
    void rateLimitBrichtDenFavoritenDurchlaufSofortAb() {
        when(favoriteService.list()).thenReturn(List.of(favorite("S1"), favorite("S2")));
        when(source.stationDetails("S1")).thenThrow(new ChargingRateLimitException("429"));

        service.pollFavorites();

        verify(source, never()).stationDetails("S2");
    }

    @Test
    void refreshNowOhneZuhauseWirftIllegalState() {
        when(settingsService.getSettings()).thenReturn(new ChargingSettings(null, null, 10.0, 50.0));

        assertThatThrownBy(() -> service.refreshNow()).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void refreshNowZweimalHintereinanderIstRateLimitiert() {
        when(source.searchArea(any(), anyDouble())).thenReturn(List.of());
        when(favoriteService.list()).thenReturn(List.of());
        service.refreshNow();

        assertThatThrownBy(() -> service.refreshNow()).isInstanceOf(ChargingRateLimitException.class);
    }

    @Test
    void refreshNowReichtQuellenfehlerDurch() {
        when(source.searchArea(any(), anyDouble())).thenThrow(new ChargingSourceException("weg"));

        assertThatThrownBy(() -> service.refreshNow()).isInstanceOf(ChargingSourceException.class);
    }
}
