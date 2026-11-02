package com.household.manager.charging;

import com.household.manager.charging.supercharger.SuperchargerCatalog;
import com.household.manager.charging.supercharger.SuperchargerSite;
import com.household.manager.exception.TooManyRequestsException;
import com.household.manager.model.entity.ChargingFavorite;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChargingAreaSearchServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-02T10:00:00Z");
    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
    /** Rund 2,2 km x 1,4 km um 50.0 / 8.0. */
    private static final BoundingBox BOX = new BoundingBox(49.99, 50.01, 7.99, 8.01);

    @Mock
    private ChargingStationSource source;
    @Mock
    private ChargingSettingsService settingsService;
    @Mock
    private ChargingFavoriteService favoriteService;
    @Mock
    private SuperchargerCatalog superchargerCatalog;

    private ChargingProperties properties;
    private ChargingSearchResults results;
    private ChargingSearchResultsTest.MutableClock clock;
    private ChargingAreaSearchService service;

    @BeforeEach
    void setUp() {
        properties = new ChargingProperties();
        clock = new ChargingSearchResultsTest.MutableClock(NOW, BERLIN);
        results = new ChargingSearchResults(clock);
        service = new ChargingAreaSearchService(properties, source, settingsService, favoriteService,
                superchargerCatalog, results, clock);
        lenient().when(settingsService.getSettings()).thenReturn(new ChargingSettings(50.0, 8.0, 10.0, 50.0));
        lenient().when(superchargerCatalog.sites()).thenReturn(List.of());
        lenient().when(favoriteService.list()).thenReturn(List.of());
    }

    private static ChargingStation station(String id, double lat, double lon, Double kw) {
        return new ChargingStation(id, "Name " + id, "Op", "Str. 1", lat, lon, kw, 4, 2);
    }

    // ---- Validierung ----

    @Test
    void nichtEndlicheWerteWerdenAbgelehntOhneDieQuelleZuFragen() {
        assertThatThrownBy(() -> service.search(new BoundingBox(Double.NaN, 50.01, 7.99, 8.01)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.search(new BoundingBox(49.99, Double.POSITIVE_INFINITY, 7.99, 8.01)))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(source);
    }

    @Test
    void vonMussKleinerSeinAlsBis() {
        assertThatThrownBy(() -> service.search(new BoundingBox(50.01, 49.99, 7.99, 8.01)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.search(new BoundingBox(49.99, 50.01, 8.01, 8.01)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void breiteUndLaengeMuessenImGueltigenBereichLiegen() {
        assertThatThrownBy(() -> service.search(new BoundingBox(-91, 0, 0, 1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.search(new BoundingBox(0, 91, 0, 1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.search(new BoundingBox(0, 1, -181, 0)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.search(new BoundingBox(0, 1, 0, 181)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void einAusschnittUeberDreissigKilometerDiagonaleWirdMitKlartextAbgelehnt() {
        // 0.3° x 0.3° bei 50° Breite: Diagonale rund 40 km
        assertThatThrownBy(() -> service.search(new BoundingBox(49.85, 50.15, 7.85, 8.15)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("näher heranzoomen");
        verifyNoInteractions(source);
    }

    @Test
    void einAusschnittUnterDerGrenzeWirdAkzeptiert() {
        when(source.searchArea(any(), anyDouble())).thenReturn(List.of());

        // 0.15° x 0.15° bei 50°: Diagonale rund 20 km
        ChargingDtos.SearchResponse response = service.search(new BoundingBox(49.925, 50.075, 7.925, 8.075));

        assertThat(response.stations()).isEmpty();
    }

    @Test
    void ohneZuhauseIstDieSucheEinFehler() {
        when(settingsService.getSettings()).thenReturn(new ChargingSettings(null, null, 10.0, 50.0));

        assertThatThrownBy(() -> service.search(BOX)).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(source);
    }

    @Test
    void sucheBeiDeaktivierterAnbindungIstEinFehler() {
        properties.setEnabled(false);

        assertThatThrownBy(() -> service.search(BOX)).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(source);
    }

    // ---- Sperre ----

    @Test
    void einZweiterAufrufBinnenZehnSekundenWirdMit429Abgelehnt() {
        when(source.searchArea(any(), anyDouble())).thenReturn(List.of());
        service.search(BOX);

        clock.advance(Duration.ofSeconds(9));
        assertThatThrownBy(() -> service.search(BOX)).isInstanceOf(ChargingRateLimitException.class)
                .isInstanceOf(TooManyRequestsException.class);

        clock.advance(Duration.ofSeconds(2));
        assertThat(service.search(BOX)).isNotNull();
    }

    @Test
    void dieSperreIstSchonVorDemAbrufReserviert() {
        when(source.searchArea(any(), anyDouble())).thenThrow(new ChargingSourceException("EnBW weg"));

        assertThatThrownBy(() -> service.search(BOX)).isInstanceOf(ChargingSourceException.class);
        assertThatThrownBy(() -> service.search(BOX)).isInstanceOf(ChargingRateLimitException.class);
    }

    @Test
    void eineAbgelehnteValidierungVerbrauchtDieSperreNicht() {
        when(source.searchArea(any(), anyDouble())).thenReturn(List.of());
        assertThatThrownBy(() -> service.search(new BoundingBox(50.01, 49.99, 7.99, 8.01)))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(service.search(BOX)).isNotNull();
    }

    @Test
    void quellenfehlerWerdenDurchgereicht() {
        when(source.searchArea(any(), anyDouble())).thenThrow(new ChargingSourceException("EnBW weg"));

        assertThatThrownBy(() -> service.search(BOX)).isInstanceOf(ChargingSourceException.class);
    }

    // ---- Ergebnis ----

    @Test
    void liefertEnbwUndSuperchargerImAusschnittNachEntfernungSortiert() {
        when(source.searchArea(BOX, 50.0)).thenReturn(List.of(
                station("fern", 50.009, 8.0, 150.0),
                station("nah", 50.001, 8.0, 150.0),
                station("schwach", 50.0, 8.005, 22.0),
                station("draussen", 50.5, 8.0, 150.0)));
        when(superchargerCatalog.sites()).thenReturn(List.of(
                new SuperchargerSite("7", "Mitte, Germany", "Weg 1", "Ort", 50.005, 8.0, 12, 250, false, "OPEN"),
                new SuperchargerSite("8", "Schwach, Germany", "Weg 1", "Ort", 50.0, 8.0, 12, 22, false, "OPEN"),
                new SuperchargerSite("9", "Weit, Germany", "Weg 1", "Ort", 51.0, 8.0, 12, 250, false, "OPEN")));

        ChargingDtos.SearchResponse response = service.search(BOX);

        assertThat(response.searchedAt()).isEqualTo(LocalDateTime.of(2026, 11, 2, 11, 0));
        assertThat(response.stations()).extracting(ChargingDtos.StationResponse::stationId)
                .containsExactly("nah", "sc-7", "fern");
        assertThat(response.stations().get(0).distanceMeters()).isBetween(100L, 120L);
        assertThat(response.stations().get(1).supercharger()).isTrue();
        assertThat(response.stations().get(1).name()).isEqualTo("Tesla Supercharger Mitte");
    }

    @Test
    void favoritenAusDerSucheTragenDasFavoritenFlag() {
        when(source.searchArea(BOX, 50.0)).thenReturn(List.of(station("fav", 50.0, 8.0, 150.0),
                station("other", 50.001, 8.0, 150.0)));
        when(favoriteService.list()).thenReturn(List.of(
                ChargingFavorite.builder().stationId("fav").displayName("F").createdAt(NOW).build()));

        ChargingDtos.SearchResponse response = service.search(BOX);

        assertThat(response.stations()).filteredOn(ChargingDtos.StationResponse::favorite)
                .extracting(ChargingDtos.StationResponse::stationId).containsExactly("fav");
    }

    @Test
    void merktSichDieGefundenenEnbwStationenFuerDasFavorisieren() {
        when(source.searchArea(BOX, 50.0)).thenReturn(List.of(station("S1", 50.0, 8.0, 150.0)));

        service.search(BOX);

        assertThat(results.find("S1")).isPresent();
    }
}
