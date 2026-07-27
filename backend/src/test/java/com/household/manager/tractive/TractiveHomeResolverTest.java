package com.household.manager.tractive;

import com.household.manager.tractive.dto.TractiveHardwareDto;
import com.household.manager.tractive.dto.TractivePositionDto;
import com.household.manager.tractive.dto.TractiveTrackableDto;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TractiveHomeResolverTest {

    /** Home-Punkt aller Tests. */
    private static final double HOME_LAT = 48.2082;
    private static final double HOME_LON = 16.3738;
    /** Rund 300 m noerdlich des Home-Punkts: ausserhalb 100 m, innerhalb 500 m. */
    private static final double NEAR_LAT = 48.2109;
    /** Rund 14 km entfernt. */
    private static final double FAR_LAT = 48.3000;
    private static final double FAR_LON = 16.5000;

    private static final Instant NOW = Instant.parse("2026-07-26T12:00:00Z");

    private TractiveHomeSettings settings(double homeRadiusMeters, double arrivalRadiusMeters) {
        return new TractiveHomeSettings(HOME_LAT, HOME_LON, homeRadiusMeters, arrivalRadiusMeters,
                60, 15, "Zuhause");
    }

    private TractiveHomeSettings defaultSettings() {
        return settings(100, 500);
    }

    private TractiveHomeSettings notConfigured() {
        return new TractiveHomeSettings(null, null, 100, 500, 60, 15, "Zuhause");
    }

    /** Positionsbericht mit einem Alter in Minuten relativ zu {@link #NOW}. */
    private TractivePositionDto positionAgedMinutes(double latitude, double longitude, long ageMinutes) {
        return new TractivePositionDto(List.of(latitude, longitude), 12.0, "GPS",
                NOW.minusSeconds(ageMinutes * 60).getEpochSecond());
    }

    private TractivePetSnapshot snapshot(TractivePositionDto position, TractiveHardwareDto hardware) {
        return new TractivePetSnapshot(
                new TractiveTrackableDto("trk-1", "dev-9",
                        new TractiveTrackableDto.Details("Bello", "DOG")),
                position, hardware, List.of());
    }

    /**
     * Der Resolver wird bewusst mit einem Mock des Settings-Service gebaut: dieser Test
     * prueft die Regeln, nicht das Auslesen der Datenbank (das deckt
     * TractiveHomeSettingsServiceTest ab).
     */
    private Optional<HomeVerdict> resolve(TractiveHomeSettings settings, TractivePetSnapshot snapshot) {
        TractiveHomeSettingsService settingsService = mock(TractiveHomeSettingsService.class);
        when(settingsService.getSettings()).thenReturn(settings);
        return new TractiveHomeResolver(settingsService).resolve(snapshot, NOW);
    }

    // --- Regel 1: ohne Home-Koordinaten keine Aussage -----------------------------------

    @Test
    void withoutHomeCoordinatesThereIsNoVerdict() {
        var snapshot = snapshot(positionAgedMinutes(HOME_LAT, HOME_LON, 1),
                new TractiveHardwareDto(87, "NOT_CHARGING"));

        assertThat(resolve(notConfigured(), snapshot)).isEmpty();
    }

    // --- Regel 2: Laden gewinnt ---------------------------------------------------------

    @Test
    void chargingMeansAtHomeEvenWhenThePositionIsFarAway() {
        var snapshot = snapshot(positionAgedMinutes(FAR_LAT, FAR_LON, 1),
                new TractiveHardwareDto(50, "CHARGING"));

        HomeVerdict verdict = resolve(defaultSettings(), snapshot).orElseThrow();

        assertThat(verdict.atHome()).isTrue();
        assertThat(verdict.basis()).isEqualTo(HomeVerdict.Basis.CHARGING);
        assertThat(verdict.stale()).isFalse();
    }

    /**
     * Regel 2 muss vor Regel 6 greifen: auf der Ladeschale liegt der Tracker oft ohne
     * frischen Positionsbericht. Wuerde die Null-Pruefung zuerst laufen, faende dieser
     * Fall gar keine Aussage – und die Entitaet behielte still ihren alten Wert.
     */
    @Test
    void chargingWinsEvenWithoutAnyPositionReport() {
        var verdict = resolve(defaultSettings(),
                snapshot(null, new TractiveHardwareDto(60, "CHARGING"))).orElseThrow();

        assertThat(verdict.atHome()).isTrue();
        assertThat(verdict.basis()).isEqualTo(HomeVerdict.Basis.CHARGING);
    }

    // --- Regel 3: frische Position ------------------------------------------------------

    @Test
    void freshPositionInsideTheHomeRadiusMeansAtHome() {
        var snapshot = snapshot(positionAgedMinutes(HOME_LAT, HOME_LON, 5),
                new TractiveHardwareDto(87, "NOT_CHARGING"));

        HomeVerdict verdict = resolve(defaultSettings(), snapshot).orElseThrow();

        assertThat(verdict.atHome()).isTrue();
        assertThat(verdict.basis()).isEqualTo(HomeVerdict.Basis.POSITION);
        assertThat(verdict.stale()).isFalse();
        assertThat(verdict.distanceMeters()).isLessThan(1.0);
        assertThat(verdict.positionAgeMinutes()).isEqualTo(5L);
    }

    @Test
    void freshPositionOutsideTheHomeRadiusMeansAway() {
        var snapshot = snapshot(positionAgedMinutes(NEAR_LAT, HOME_LON, 5),
                new TractiveHardwareDto(87, "NOT_CHARGING"));

        HomeVerdict verdict = resolve(defaultSettings(), snapshot).orElseThrow();

        assertThat(verdict.atHome()).isFalse();
        assertThat(verdict.basis()).isEqualTo(HomeVerdict.Basis.POSITION);
    }

    /** Der Rand zaehlt als innerhalb – konsistent zu GeoZone.contains. */
    @Test
    void aPositionExactlyOnTheHomeRadiusCountsAsAtHome() {
        double distance = GeoZone.distanceMeters(HOME_LAT, HOME_LON, NEAR_LAT, HOME_LON);
        var position = positionAgedMinutes(NEAR_LAT, HOME_LON, 5);

        HomeVerdict verdict = resolve(settings(distance, 500), snapshot(position,
                new TractiveHardwareDto(87, "NOT_CHARGING"))).orElseThrow();

        assertThat(verdict.atHome()).isTrue();
    }

    /** Ohne Zeitstempel laesst sich "still" nicht bestimmen – dann gilt der Bericht als frisch. */
    @Test
    void positionWithoutTimestampIsTreatedAsFresh() {
        var position = new TractivePositionDto(List.of(FAR_LAT, FAR_LON), 12.0, "GPS", null);

        HomeVerdict verdict = resolve(defaultSettings(),
                snapshot(position, new TractiveHardwareDto(87, "NOT_CHARGING"))).orElseThrow();

        assertThat(verdict.stale()).isFalse();
        assertThat(verdict.atHome()).isFalse();
        assertThat(verdict.positionAgeMinutes()).isNull();
    }

    // --- Regel 4: Stille als Ausschalten deuten -----------------------------------------

    @Test
    void silenceWithHealthyBatteryNearHomeMeansPoweredOffAtHome() {
        var snapshot = snapshot(positionAgedMinutes(NEAR_LAT, HOME_LON, 90),
                new TractiveHardwareDto(87, "NOT_CHARGING"));

        HomeVerdict verdict = resolve(defaultSettings(), snapshot).orElseThrow();

        assertThat(verdict.atHome()).isTrue();
        assertThat(verdict.basis()).isEqualTo(HomeVerdict.Basis.POWERED_OFF);
        assertThat(verdict.stale()).isTrue();
        assertThat(verdict.positionAgeMinutes()).isEqualTo(90L);
    }

    /** Genau auf der Schwelle gilt der Bericht bereits als still. */
    @Test
    void aReportExactlyAtTheSilenceThresholdIsStale() {
        var snapshot = snapshot(positionAgedMinutes(FAR_LAT, FAR_LON, 60),
                new TractiveHardwareDto(87, "NOT_CHARGING"));

        assertThat(resolve(defaultSettings(), snapshot).orElseThrow().stale()).isTrue();
    }

    // --- Regel 5: Stille ohne die Belege -> letztes Positionsurteil ---------------------

    @Test
    void silenceWithEmptyBatteryFallsBackToTheLastKnownPosition() {
        var snapshot = snapshot(positionAgedMinutes(NEAR_LAT, HOME_LON, 90),
                new TractiveHardwareDto(3, "NOT_CHARGING"));

        HomeVerdict verdict = resolve(defaultSettings(), snapshot).orElseThrow();

        assertThat(verdict.atHome()).isFalse();
        assertThat(verdict.basis()).isEqualTo(HomeVerdict.Basis.POSITION);
        assertThat(verdict.stale()).isTrue();
    }

    /** Regel 5 urteilt weiter nach der letzten Position – auch in Richtung "zu Hause". */
    @Test
    void silenceWithEmptyBatteryInsideTheHomeRadiusStillMeansAtHome() {
        var snapshot = snapshot(positionAgedMinutes(HOME_LAT, HOME_LON, 90),
                new TractiveHardwareDto(3, "NOT_CHARGING"));

        HomeVerdict verdict = resolve(defaultSettings(), snapshot).orElseThrow();

        assertThat(verdict.atHome()).isTrue();
        assertThat(verdict.basis()).isEqualTo(HomeVerdict.Basis.POSITION);
        assertThat(verdict.stale()).isTrue();
    }

    @Test
    void silenceFarFromHomeFallsBackToTheLastKnownPosition() {
        var snapshot = snapshot(positionAgedMinutes(FAR_LAT, FAR_LON, 240),
                new TractiveHardwareDto(87, "NOT_CHARGING"));

        HomeVerdict verdict = resolve(defaultSettings(), snapshot).orElseThrow();

        assertThat(verdict.atHome()).isFalse();
        assertThat(verdict.basis()).isEqualTo(HomeVerdict.Basis.POSITION);
        assertThat(verdict.stale()).isTrue();
    }

    /** Fail-safe: ohne Akkustand wird nicht auf "ausgeschaltet" geschlossen. */
    @Test
    void silenceWithoutBatteryLevelDoesNotInferPoweredOff() {
        var snapshot = snapshot(positionAgedMinutes(NEAR_LAT, HOME_LON, 90),
                new TractiveHardwareDto(null, "NOT_CHARGING"));

        HomeVerdict verdict = resolve(defaultSettings(), snapshot).orElseThrow();

        assertThat(verdict.basis()).isEqualTo(HomeVerdict.Basis.POSITION);
        assertThat(verdict.atHome()).isFalse();
    }

    @Test
    void silenceWithoutAnyHardwareReportDoesNotInferPoweredOff() {
        var snapshot = snapshot(positionAgedMinutes(NEAR_LAT, HOME_LON, 90), null);

        HomeVerdict verdict = resolve(defaultSettings(), snapshot).orElseThrow();

        assertThat(verdict.basis()).isEqualTo(HomeVerdict.Basis.POSITION);
        assertThat(verdict.atHome()).isFalse();
    }

    /** Ein zu klein konfigurierter Ankunftsradius darf Regel 4 nicht unwirksam machen. */
    @Test
    void theArrivalRadiusNeverFallsBelowTheHomeRadius() {
        var snapshot = snapshot(positionAgedMinutes(NEAR_LAT, HOME_LON, 90),
                new TractiveHardwareDto(87, "NOT_CHARGING"));

        HomeVerdict verdict = resolve(settings(400, 10), snapshot).orElseThrow();

        assertThat(verdict.basis()).isEqualTo(HomeVerdict.Basis.POWERED_OFF);
    }

    // --- Regel 6: gar keine Daten -------------------------------------------------------

    @Test
    void withoutPositionAndWithoutChargingThereIsNoVerdict() {
        assertThat(resolve(defaultSettings(),
                snapshot(null, new TractiveHardwareDto(87, "NOT_CHARGING")))).isEmpty();
    }

    @Test
    void aPositionWithoutCoordinatesYieldsNoVerdict() {
        var position = new TractivePositionDto(null, null, null, 1800000000L);

        assertThat(resolve(defaultSettings(),
                snapshot(position, new TractiveHardwareDto(87, "NOT_CHARGING")))).isEmpty();
    }

    // --- Uhren-Versatz: ein Bericht "aus der Zukunft" gilt als frisch --------------------

    /** Uhren-Versatz zur Cloud: ein Bericht aus der Zukunft gilt als frisch, nie als still. */
    @Test
    void aFuturePositionTimestampIsClampedToFresh() {
        var snapshot = snapshot(positionAgedMinutes(HOME_LAT, HOME_LON, -30),
                new TractiveHardwareDto(87, "NOT_CHARGING"));

        HomeVerdict verdict = resolve(defaultSettings(), snapshot).orElseThrow();

        assertThat(verdict.stale()).isFalse();
        assertThat(verdict.positionAgeMinutes()).isEqualTo(0L);
    }
}
