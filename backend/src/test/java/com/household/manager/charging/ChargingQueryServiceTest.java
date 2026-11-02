package com.household.manager.charging;

import com.household.manager.model.entity.ChargingFavorite;
import com.household.manager.model.entity.ChargingPointOccupancy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import com.household.manager.charging.forecast.ChargingForecast;
import com.household.manager.charging.forecast.ChargingForecastService;
import com.household.manager.charging.supercharger.SuperchargerCatalog;
import com.household.manager.charging.supercharger.SuperchargerSite;
import org.mockito.Mock;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.util.Optional;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
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
    @Mock
    private ChargingForecastService forecastService;
    @Mock
    private SuperchargerCatalog superchargerCatalog;

    private ChargingSnapshot snapshot;
    private ChargingQueryService service;

    @BeforeEach
    void setUp() {
        snapshot = new ChargingSnapshot();
        service = new ChargingQueryService(settingsService, favoriteService, tracker, snapshot,
                forecastService, superchargerCatalog, Clock.fixed(NOW, BERLIN));
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
                new ChargePoint("P1", ChargePointStatus.OCCUPIED, 150.0, "CCS", null, 0.34),
                new ChargePoint("P2", ChargePointStatus.FREE, 150.0, "CCS", null, 0.34)))), NOW);
        Instant since = Instant.parse("2026-09-19T09:20:00Z");
        when(tracker.occupancyFor("fav")).thenReturn(Map.of("P1", ChargingPointOccupancy.builder()
                .chargePointId("P1").stationId("fav").occupiedSince(since).firstSeenOccupiedAt(since).build()));

        ChargingDtos.StationsResponse response = service.stations();

        assertThat(response.stations()).extracting(ChargingDtos.StationResponse::stationId)
                .containsExactly("fav", "nah", "fern");
        ChargingDtos.StationResponse fav = response.stations().get(0);
        assertThat(fav.favorite()).isTrue();
        assertThat(fav.pricePerKwh()).isNull();
        assertThat(fav.chargePoints()).hasSize(2);
        ChargingDtos.ChargePointResponse p1 = fav.chargePoints().get(0);
        assertThat(p1.occupiedSince()).isEqualTo(LocalDateTime.ofInstant(since, BERLIN));
        assertThat(p1.minimumDuration()).isFalse();
        assertThat(response.stations().get(1).chargePoints()).isNull();
        assertThat(response.stations().get(1).pricePerKwh()).isNull();
        assertThat(response.lastPolledAt()).isEqualTo(LocalDateTime.ofInstant(NOW, BERLIN));
    }

    @Test
    void favoritAusserhalbDesUmkreisesBleibtMitGespeichertenStammdatenInDerListe() {
        when(settingsService.getSettings()).thenReturn(new ChargingSettings(50.0, 8.0, 10.0, 50.0));
        ChargingFavorite favorite = ChargingFavorite.builder().stationId("weit").displayName("Weit weg")
                .operator("Op").lat(51.0).lon(9.0).createdAt(NOW).build();
        when(favoriteService.list()).thenReturn(List.of(favorite));
        snapshot.updateFavoriteDetails(Map.of("weit", new ChargingStationDetails("weit", List.of(
                new ChargePoint("P1", ChargePointStatus.OCCUPIED, null, null, null, null)))), NOW);
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

    private static SuperchargerSite sc(String id, String name, String city, double lat, Integer power,
                                       boolean otherEvs) {
        return new SuperchargerSite(id, name, "Hahnstr. 1", city, lat, 8.0, 15, power, otherEvs, "OPEN");
    }

    @Test
    void superchargerWerdenNachEntfernungUnterDieNichtFavoritenGemischt() {
        when(settingsService.getSettings()).thenReturn(new ChargingSettings(50.0, 8.0, 10.0, 0.0));
        when(favoriteService.list()).thenReturn(List.of(favorite("fav")));
        snapshot.updateArea(List.of(
                new ChargingStation("fern", "Fern", null, null, 50.05, 8.0, 150.0, 2, 2),
                new ChargingStation("nah", "Nah", null, null, 50.01, 8.0, 150.0, 2, 2)), NOW);
        when(superchargerCatalog.sites()).thenReturn(List.of(
                sc("7", "Pfungstadt, Germany", "Pfungstadt", 50.03, 250, false)));

        List<ChargingDtos.StationResponse> stations = service.stations().stations();

        assertThat(stations).extracting(ChargingDtos.StationResponse::stationId)
                .containsExactly("fav", "nah", "sc-7", "fern");
    }

    @Test
    void superchargerZeileTraegtDieVereinbartenFelder() {
        when(settingsService.getSettings()).thenReturn(new ChargingSettings(50.0, 8.0, 10.0, 0.0));
        when(favoriteService.list()).thenReturn(List.of());
        when(superchargerCatalog.sites()).thenReturn(List.of(
                sc("7", "Pfungstadt, Germany", "Pfungstadt", 50.03, 250, false)));

        ChargingDtos.StationResponse row = service.stations().stations().get(0);

        assertThat(row.stationId()).isEqualTo("sc-7");
        assertThat(row.name()).isEqualTo("Tesla Supercharger Pfungstadt");
        assertThat(row.operator()).isEqualTo("Tesla");
        assertThat(row.address()).isEqualTo("Hahnstr. 1, Pfungstadt");
        assertThat(row.maxPowerKw()).isEqualTo(250.0);
        assertThat(row.total()).isEqualTo(15);
        assertThat(row.free()).isZero();
        assertThat(row.favorite()).isFalse();
        assertThat(row.chargePoints()).isNull();
        assertThat(row.pricePerKwh()).isNull();
        assertThat(row.forecast()).isNull();
        assertThat(row.supercharger()).isTrue();
        assertThat(row.teslaOnly()).isTrue();
        assertThat(row.distanceMeters()).isPositive();
    }

    @Test
    void teslaOnlyIstNullWennAndereFahrzeugeLadenDuerfen() {
        when(settingsService.getSettings()).thenReturn(new ChargingSettings(50.0, 8.0, 10.0, 0.0));
        when(favoriteService.list()).thenReturn(List.of());
        when(superchargerCatalog.sites()).thenReturn(List.of(
                sc("8", "Weiterstadt, Germany", "Weiterstadt", 50.03, 250, true)));

        assertThat(service.stations().stations().get(0).teslaOnly()).isNull();
    }

    @Test
    void namenOhneLandSuffixFaelltAufStadtZurueck() {
        when(settingsService.getSettings()).thenReturn(new ChargingSettings(50.0, 8.0, 10.0, 0.0));
        when(favoriteService.list()).thenReturn(List.of());
        when(superchargerCatalog.sites()).thenReturn(List.of(
                sc("9", "Ohne Land", "Stadt", 50.03, 250, false),
                sc("10", null, "Nur Stadt", 50.04, 250, false)));

        assertThat(service.stations().stations()).extracting(ChargingDtos.StationResponse::name)
                .containsExactly("Tesla Supercharger Ohne Land", "Tesla Supercharger Nur Stadt");
    }

    @Test
    void superchargerAusserhalbDesUmkreisesUndUnterMindestleistungFallenWeg() {
        when(settingsService.getSettings()).thenReturn(new ChargingSettings(50.0, 8.0, 10.0, 150.0));
        when(favoriteService.list()).thenReturn(List.of());
        when(superchargerCatalog.sites()).thenReturn(List.of(
                sc("ok", "Ok, Germany", "Ok", 50.03, 250, false),
                sc("weit", "Weit, Germany", "Weit", 51.0, 250, false),
                sc("schwach", "Schwach, Germany", "Schwach", 50.03, 120, false),
                sc("ohneLeistung", "Unbekannt, Germany", "Unbekannt", 50.03, null, false)));

        assertThat(service.stations().stations()).extracting(ChargingDtos.StationResponse::stationId)
                .containsExactly("sc-ok");
    }

    @Test
    void ohneMindestleistungBleibtSuperchargerOhneLeistungErhalten() {
        when(settingsService.getSettings()).thenReturn(new ChargingSettings(50.0, 8.0, 10.0, 0.0));
        when(favoriteService.list()).thenReturn(List.of());
        when(superchargerCatalog.sites()).thenReturn(List.of(
                sc("ohneLeistung", "Unbekannt, Germany", "Unbekannt", 50.03, null, false)));

        ChargingDtos.StationResponse row = service.stations().stations().get(0);

        assertThat(row.maxPowerKw()).isNull();
    }

    @Test
    void ohneZuhauseKeineSupercharger() {
        when(settingsService.getSettings()).thenReturn(new ChargingSettings(null, null, 10.0, 0.0));

        assertThat(service.stations().stations()).isEmpty();
        verifyNoInteractions(superchargerCatalog);
    }

    @Test
    void normaleStationenTragenKeineSuperchargerFelder() {
        when(settingsService.getSettings()).thenReturn(new ChargingSettings(50.0, 8.0, 10.0, 0.0));
        when(favoriteService.list()).thenReturn(List.of());
        snapshot.updateArea(List.of(new ChargingStation("nah", "Nah", null, null, 50.01, 8.0, 150.0, 2, 2)), NOW);

        ChargingDtos.StationResponse row = service.stations().stations().get(0);

        assertThat(row.supercharger()).isNull();
        assertThat(row.teslaOnly()).isNull();
    }

    private ChargingFavorite favorite(String id) {
        return ChargingFavorite.builder().stationId(id).displayName("Lidl").operator("Lidl")
                .lat(50.08).lon(8.0).createdAt(NOW).build();
    }

    @Test
    void favoritBekommtZusammenfassung() {
        when(settingsService.getSettings()).thenReturn(new ChargingSettings(50.0, 8.0, 10.0, 50.0));
        when(favoriteService.list()).thenReturn(List.of(favorite("fav")));
        Instant at = Instant.parse("2026-09-19T10:20:00Z");
        Instant latest = Instant.parse("2026-09-19T10:50:00Z");
        when(forecastService.latest("fav")).thenReturn(Optional.of(new ChargingForecast(
                ChargingForecast.Status.OK, 20, Map.of(15, 40, 30, 62, 60, 80), List.of(), at, latest,
                new Double[7][24])));

        ChargingDtos.ForecastSummary summary = service.stations().stations().get(0).forecast();

        assertThat(summary.status()).isEqualTo("OK");
        assertThat(summary.freeProbability30min()).isEqualTo(62);
        assertThat(summary.expectedFreeAt()).isEqualTo(LocalDateTime.ofInstant(at, BERLIN));
        assertThat(summary.expectedFreeLatest()).isEqualTo(LocalDateTime.ofInstant(latest, BERLIN));
        assertThat(summary.observedDays()).isEqualTo(20L);
    }

    @Test
    void favoritOhneStandBekommtZuWenigDatenZusammenfassung() {
        when(settingsService.getSettings()).thenReturn(new ChargingSettings(50.0, 8.0, 10.0, 50.0));
        when(favoriteService.list()).thenReturn(List.of(favorite("fav")));
        when(forecastService.latest("fav")).thenReturn(Optional.empty());

        ChargingDtos.ForecastSummary summary = service.stations().stations().get(0).forecast();

        assertThat(summary.status()).isEqualTo("INSUFFICIENT_DATA");
        assertThat(summary.observedDays()).isZero();
        assertThat(summary.freeProbability30min()).isNull();
    }

    @Test
    void nichtFavoritOhneZusammenfassung() {
        when(settingsService.getSettings()).thenReturn(new ChargingSettings(50.0, 8.0, 10.0, 50.0));
        when(favoriteService.list()).thenReturn(List.of());
        snapshot.updateArea(List.of(new ChargingStation("x", "X", null, null, 50.01, 8.0, 150.0, 2, 2)), NOW);

        assertThat(service.stations().stations().get(0).forecast()).isNull();
    }

    @Test
    void forecastFuerUnbekanntenFavoriten404() {
        when(favoriteService.list()).thenReturn(List.of());

        assertThatThrownBy(() -> service.forecast("nope"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void forecastOhneStandZuWenigDaten() {
        when(favoriteService.list()).thenReturn(List.of(favorite("fav")));
        when(forecastService.latest("fav")).thenReturn(Optional.empty());

        ChargingDtos.ForecastResponse response = service.forecast("fav");

        assertThat(response.status()).isEqualTo("INSUFFICIENT_DATA");
        assertThat(response.observedDays()).isZero();
        assertThat(response.heatmap()).hasDimensions(7, 24);
        assertThat(response.heatmap()[3][5]).isNull();
        assertThat(response.points()).isEmpty();
    }

    @Test
    void favoritZeigtNurDenDirektpreisUndNieEnbwPreise() {
        when(settingsService.getSettings()).thenReturn(new ChargingSettings(50.0, 8.0, 10.0, 50.0));
        snapshot.updateArea(List.of(new ChargingStation("fav", "Lidl", "Lidl", null, 50.08, 8.0, 150.0, 2, 1)), NOW);
        ChargingFavorite favorite = ChargingFavorite.builder().stationId("fav").displayName("Lidl")
                .operator("Lidl").lat(50.08).lon(8.0).createdAt(NOW)
                .directPricePerKwh(new java.math.BigDecimal("0.390")).directPriceNote("Aldi-App").build();
        when(favoriteService.list()).thenReturn(List.of(favorite));
        snapshot.updateFavoriteDetails(Map.of("fav", new ChargingStationDetails("fav", List.of(
                new ChargePoint("P1", ChargePointStatus.FREE, 150.0, "CCS", null, 0.55)))), NOW);

        ChargingDtos.StationResponse fav = service.stations().stations().get(0);

        assertThat(fav.pricePerKwh()).isEqualTo(0.39);
        assertThat(fav.priceNote()).isEqualTo("Aldi-App");
        assertThat(fav.chargePoints()).allSatisfy(p -> assertThat(p.pricePerKwh()).isNull());
    }

    @Test
    void favoritOhneDirektpreisHatKeinenPreisAuchNichtVonEnbw() {
        when(settingsService.getSettings()).thenReturn(new ChargingSettings(50.0, 8.0, 10.0, 50.0));
        snapshot.updateArea(List.of(new ChargingStation("fav", "Lidl", "Lidl", null, 50.08, 8.0, 150.0, 2, 1)), NOW);
        when(favoriteService.list()).thenReturn(List.of(favorite("fav")));
        snapshot.updateFavoriteDetails(Map.of("fav", new ChargingStationDetails("fav", List.of(
                new ChargePoint("P1", ChargePointStatus.FREE, 150.0, "CCS", null, 0.55)))), NOW);

        ChargingDtos.StationResponse fav = service.stations().stations().get(0);

        assertThat(fav.pricePerKwh()).isNull();
        assertThat(fav.priceNote()).isNull();
        assertThat(fav.chargePoints()).allSatisfy(p -> assertThat(p.pricePerKwh()).isNull());
    }
}
