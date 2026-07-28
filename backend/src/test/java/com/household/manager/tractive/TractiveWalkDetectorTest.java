package com.household.manager.tractive;

import com.household.manager.tractive.dto.TractivePositionDto;
import com.household.manager.tractive.dto.TractiveWalkDto;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TractiveWalkDetectorTest {

    /** Zuhause in Wien, Radius 100 m. */
    private static final GeoZone HOME = new GeoZone("Zuhause", 48.2082, 16.3738, 100);
    private static final Instant T0 = Instant.ofEpochSecond(1_800_000_000L);

    /** Punkt ~50 m vom Zuhause (innerhalb). */
    private TractivePositionDto homePoint(long minutesAfterT0) {
        return point(48.2086, 16.3738, minutesAfterT0);
    }

    /**
     * Punkt ~1,1 km vom Zuhause (außerhalb). Leichte Bewegung pro Minute
     * (~11 m), damit ein Spaziergang aus mehreren Punkten eine echte,
     * von null verschiedene Distanz ergibt.
     */
    private TractivePositionDto awayPoint(long minutesAfterT0) {
        return point(48.2182 + minutesAfterT0 * 0.0001, 16.3738, minutesAfterT0);
    }

    private TractivePositionDto point(double lat, double lon, long minutesAfterT0) {
        return new TractivePositionDto(List.of(lat, lon), null, "GPS",
                T0.plusSeconds(minutesAfterT0 * 60).getEpochSecond());
    }

    @Test
    void leereEingabeErgibtKeineSpaziergaenge() {
        assertTrue(TractiveWalkDetector.detectWalks(List.of(), HOME).isEmpty());
    }

    @Test
    void nurZuhausePunkteErgebenKeineSpaziergaenge() {
        var walks = TractiveWalkDetector.detectWalks(
                List.of(homePoint(0), homePoint(10), homePoint(20)), HOME);
        assertTrue(walks.isEmpty());
    }

    @Test
    void zusammenhaengendeUnterwegsPunkteWerdenEinSpaziergang() {
        var walks = TractiveWalkDetector.detectWalks(List.of(
                homePoint(0), awayPoint(5), awayPoint(9), awayPoint(15),
                homePoint(20)), HOME);

        assertEquals(1, walks.size());
        TractiveWalkDto walk = walks.get(0);
        assertEquals(T0.plusSeconds(5 * 60), walk.start());
        assertEquals(T0.plusSeconds(15 * 60), walk.end());
        assertEquals(10, walk.durationMinutes());
        assertTrue(walk.distanceMeters() > 0);
    }

    @Test
    void kurzerAusreisserUnterFuenfMinutenWirdVerworfen() {
        var walks = TractiveWalkDetector.detectWalks(List.of(
                homePoint(0), awayPoint(5), awayPoint(7), homePoint(10)), HOME);
        assertTrue(walks.isEmpty());
    }

    @Test
    void lueckeUnterZehnMinutenWirdUeberbrueckt() {
        var walks = TractiveWalkDetector.detectWalks(List.of(
                awayPoint(0), awayPoint(9), awayPoint(18)), HOME);
        assertEquals(1, walks.size());
        assertEquals(18, walks.get(0).durationMinutes());
    }

    @Test
    void lueckeVonExaktZehnMinutenWirdNochUeberbrueckt() {
        var walks = TractiveWalkDetector.detectWalks(List.of(
                awayPoint(0), awayPoint(10)), HOME);
        assertEquals(1, walks.size());
        assertEquals(10, walks.get(0).durationMinutes());
    }

    @Test
    void spaziergangVonExaktFuenfMinutenBleibtErhalten() {
        var walks = TractiveWalkDetector.detectWalks(List.of(
                awayPoint(0), awayPoint(5)), HOME);
        assertEquals(1, walks.size());
        assertEquals(5, walks.get(0).durationMinutes());
    }

    @Test
    void lueckeAbZehnMinutenTeiltInZweiSpaziergaenge() {
        var walks = TractiveWalkDetector.detectWalks(List.of(
                awayPoint(0), awayPoint(6), awayPoint(20), awayPoint(28)), HOME);
        assertEquals(2, walks.size());
    }

    @Test
    void heimpunktBeendetDenSpaziergangAuchInnerhalbDerLuecke() {
        var walks = TractiveWalkDetector.detectWalks(List.of(
                awayPoint(0), awayPoint(6), homePoint(8), awayPoint(10), awayPoint(16)), HOME);
        assertEquals(2, walks.size());
    }

    @Test
    void unplausiblePunkteWerdenIgnoriert() {
        var kaputt = List.of(
                new TractivePositionDto(null, null, null, T0.getEpochSecond()),
                new TractivePositionDto(List.of(48.2182), null, null, T0.getEpochSecond()),
                new TractivePositionDto(Arrays.asList(48.2182, (Double) null), null, null,
                        T0.getEpochSecond()),
                new TractivePositionDto(List.of(Double.NaN, 16.3738), null, null,
                        T0.getEpochSecond()),
                new TractivePositionDto(List.of(48.2182, 16.3738), null, null, null));
        assertTrue(TractiveWalkDetector.detectWalks(kaputt, HOME).isEmpty());
    }

    @Test
    void unsortiertePunkteWerdenVorherSortiert() {
        var walks = TractiveWalkDetector.detectWalks(List.of(
                awayPoint(15), awayPoint(5), awayPoint(9)), HOME);
        assertEquals(1, walks.size());
        assertEquals(10, walks.get(0).durationMinutes());
    }

    @Test
    void neuesteSpaziergaengeStehenVorn() {
        var walks = TractiveWalkDetector.detectWalks(List.of(
                awayPoint(0), awayPoint(6),
                awayPoint(60), awayPoint(70)), HOME);
        assertEquals(2, walks.size());
        assertTrue(walks.get(0).start().isAfter(walks.get(1).start()));
    }
}
