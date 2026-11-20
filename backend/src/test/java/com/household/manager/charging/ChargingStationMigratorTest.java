package com.household.manager.charging;

import com.household.manager.model.entity.ChargingFavorite;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ChargingStationMigratorTest {

    private static final Instant NOW = Instant.parse("2026-11-20T15:00:00Z");

    /** Am Suedbahnhof 3, Bad Vilbel: alte Id aus dem Favoriten, neue aus dem echten Umkreis (2026-11-20). */
    private static final ChargingStation NACHFOLGER = new ChargingStation("3511835", "Am Südbahnhof 3",
            "Mainova AG", "Am Südbahnhof 3, 61118 Bad Vilbel, DE", 50.178389, 8.734765, 150.0, 2, 1);
    private static final ChargingStation NACHBAR_ANDERER_BETREIBER = new ChargingStation("3015341",
            "Berkersheimer Weg 1", "Stadtwerke Bad Vilbel GmbH", null, 50.17802, 8.73359, 22.0, 5, 4);

    @Mock
    private ChargingStationMigrationService migrationService;

    private ChargingSnapshot snapshot;
    private ChargingSearchResultsTest.MutableClock clock;
    private ChargingStationMigrator migrator;

    private static ChargingFavorite favorit() {
        return ChargingFavorite.builder().stationId("ALT").displayName("Am Südbahnhof 3").operator("Mainova AG")
                .lat(50.17845).lon(8.73480).createdAt(NOW).build();
    }

    @BeforeEach
    void setUp() {
        snapshot = new ChargingSnapshot();
        clock = new ChargingSearchResultsTest.MutableClock(NOW);
        migrator = new ChargingStationMigrator(snapshot, migrationService, clock);
    }

    /** Erster 404 jetzt, Umkreis danach frisch, Wartezeit abgelaufen. */
    private Optional<String> nachAblaufDerWartezeit(List<ChargingStation> area) {
        assertThat(migrator.stationNotFound(favorit(), Set.of("ALT"))).isEmpty();
        clock.advance(ChargingStationMigrator.GRACE);
        snapshot.updateArea(area, clock.instant());
        return migrator.stationNotFound(favorit(), Set.of("ALT"));
    }

    @Test
    void ziehtNachDerWartezeitAufDenEindeutigenNachfolgerUm() {
        Optional<String> result = nachAblaufDerWartezeit(List.of(NACHFOLGER, NACHBAR_ANDERER_BETREIBER));

        assertThat(result).contains("3511835");
        verify(migrationService).migrate("ALT", NACHFOLGER);
    }

    @Test
    void vorAblaufDerWartezeitPassiertNichts() {
        snapshot.updateArea(List.of(NACHFOLGER), NOW);
        migrator.stationNotFound(favorit(), Set.of("ALT"));
        clock.advance(ChargingStationMigrator.GRACE.minus(Duration.ofMinutes(1)));
        snapshot.updateArea(List.of(NACHFOLGER), clock.instant());

        assertThat(migrator.stationNotFound(favorit(), Set.of("ALT"))).isEmpty();
        verify(migrationService, never()).migrate(any(), any());
    }

    @Test
    void einErfolgreicherAbrufSetztDieWartezeitZurueck() {
        migrator.stationNotFound(favorit(), Set.of("ALT"));
        clock.advance(ChargingStationMigrator.GRACE);
        migrator.stationFound("ALT");
        snapshot.updateArea(List.of(NACHFOLGER), clock.instant());

        assertThat(migrator.stationNotFound(favorit(), Set.of("ALT"))).isEmpty();
        verify(migrationService, never()).migrate(any(), any());
    }

    @Test
    void ohneFrischenUmkreisStandKeinUmzug() {
        snapshot.updateArea(List.of(NACHFOLGER), NOW.minusSeconds(60));
        migrator.stationNotFound(favorit(), Set.of("ALT"));
        clock.advance(ChargingStationMigrator.GRACE);

        assertThat(migrator.stationNotFound(favorit(), Set.of("ALT"))).isEmpty();
        verify(migrationService, never()).migrate(any(), any());
    }

    @Test
    void steht_die_alte_Id_noch_im_Umkreis_wird_nicht_umgezogen() {
        ChargingStation alt = new ChargingStation("ALT", "Am Südbahnhof 3", "Mainova AG", null,
                50.17845, 8.73480, 150.0, 2, 1);

        assertThat(nachAblaufDerWartezeit(List.of(alt, NACHFOLGER))).isEmpty();
        verify(migrationService, never()).migrate(any(), any());
    }

    @Test
    void zweiKandidatenWerdenNichtGeraten() {
        ChargingStation zweite = new ChargingStation("3511836", "Am Südbahnhof 3", "Mainova AG", null,
                50.17840, 8.73478, 150.0, 2, 2);

        assertThat(nachAblaufDerWartezeit(List.of(NACHFOLGER, zweite))).isEmpty();
        verify(migrationService, never()).migrate(any(), any());
    }

    @Test
    void scheitertDerUmzugBleibtDerFavoritUnveraendert() {
        doThrow(new IllegalStateException("bereits Favorit")).when(migrationService).migrate("ALT", NACHFOLGER);

        assertThat(nachAblaufDerWartezeit(List.of(NACHFOLGER))).isEmpty();
    }

    @Test
    void kandidatenBrauchenGleichenBetreiberUndNaehe() {
        ChargingStation fern = new ChargingStation("F", "Fern", "Mainova AG", null, 50.18, 8.74, 150.0, 2, 1);
        ChargingStation andererFavorit = new ChargingStation("FAV", "Fav", "Mainova AG", null,
                50.17845, 8.73480, 150.0, 2, 1);
        ChargingStation grossKlein = new ChargingStation("GK", "GK", "  mainova ag ", null,
                50.17845, 8.73481, 150.0, 2, 1);

        List<ChargingStation> result = ChargingStationMigrator.successorCandidates(favorit(),
                List.of(NACHFOLGER, NACHBAR_ANDERER_BETREIBER, fern, andererFavorit, grossKlein),
                Set.of("ALT", "FAV"));

        assertThat(result).extracting(ChargingStation::stationId).containsExactly("3511835", "GK");
    }

    @Test
    void ohneGespeichertenBetreiberZaehltNurDieNaehe() {
        ChargingFavorite ohneBetreiber = favorit();
        ohneBetreiber.setOperator(null);

        List<ChargingStation> result = ChargingStationMigrator.successorCandidates(ohneBetreiber,
                List.of(NACHFOLGER, NACHBAR_ANDERER_BETREIBER), Set.of("ALT"));

        assertThat(result).extracting(ChargingStation::stationId).containsExactly("3511835");
    }
}
