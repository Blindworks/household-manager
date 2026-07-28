package com.household.manager.tractive;

import com.household.manager.tractive.dto.TractivePositionDto;
import com.household.manager.tractive.dto.TractiveWalkDto;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Der Detektor nutzt den Einschalt-Indikator dieses Haushalts: der Tracker ist
 * zu Hause aus und wird nur fuer die Runde eingeschaltet. Ein Spaziergang ist
 * deshalb ein zusammenhaengender Block von Positionsberichten zwischen zwei
 * langen Funkpausen — nicht erst die Zeit ausserhalb des Home-Radius.
 */
class TractiveWalkDetectorTest {

    /** Zuhause in Wien, Radius 100 m. */
    private static final GeoZone HOME = new GeoZone("Zuhause", 48.2082, 16.3738, 100);
    private static final Instant T0 = Instant.ofEpochSecond(1_800_000_000L);

    /** Punkt ~50 m vom Zuhause (innerhalb des Radius). */
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
    void berichtsClusterMitAuswaertsPunktenWirdEinSpaziergang() {
        // Einschalten an der Haustuer (Punkt noch im Radius), Runde, Ausschalten
        // wieder an der Haustuer: der ganze Block zaehlt, inklusive der Raender.
        var walks = TractiveWalkDetector.detectWalks(List.of(
                homePoint(0), awayPoint(5), awayPoint(15), awayPoint(25), homePoint(29)), HOME);

        assertEquals(1, walks.size());
        TractiveWalkDto walk = walks.get(0);
        assertEquals(T0, walk.start());
        assertEquals(T0.plusSeconds(29 * 60), walk.end());
        assertEquals(29, walk.durationMinutes());
        assertTrue(walk.distanceMeters() > 0);
    }

    @Test
    void distanzSummiertAuchDieWegstueckeNaheDemHaus() {
        var mitRaendern = TractiveWalkDetector.detectWalks(List.of(
                homePoint(0), awayPoint(5), awayPoint(15), homePoint(20)), HOME);
        var ohneRaender = TractiveWalkDetector.detectWalks(List.of(
                awayPoint(5), awayPoint(15)), HOME);

        assertTrue(mitRaendern.get(0).distanceMeters() > ohneRaender.get(0).distanceMeters(),
                "Randpunkte muessen zur Distanz beitragen");
    }

    @Test
    void clusterNurAusZuhausePunktenIstKeinSpaziergang() {
        // Tracker versehentlich zu Hause eingeschaltet (z. B. Ladeschale):
        // ohne einen einzigen Punkt ausserhalb des Radius zaehlt der Block nicht.
        var walks = TractiveWalkDetector.detectWalks(List.of(
                homePoint(0), homePoint(10), homePoint(20), homePoint(30)), HOME);
        assertTrue(walks.isEmpty());
    }

    @Test
    void langeFunkpauseTrenntZweiSpaziergaenge() {
        var walks = TractiveWalkDetector.detectWalks(List.of(
                awayPoint(0), awayPoint(10),
                awayPoint(50), awayPoint(60)), HOME);
        assertEquals(2, walks.size());
    }

    @Test
    void funkpauseVonExaktDreissigMinutenWirdNochUeberbrueckt() {
        // Der Sparmodus meldet unregelmaessig; erst eine echt laengere Pause
        // gilt als Ausschalten.
        var walks = TractiveWalkDetector.detectWalks(List.of(
                awayPoint(0), awayPoint(30)), HOME);
        assertEquals(1, walks.size());
        assertEquals(30, walks.get(0).durationMinutes());
    }

    @Test
    void zuhausePunkteInnerhalbDesClustersTrennenNicht() {
        // Kurzer Schlenker zurueck in den Radius (z. B. was vergessen) beendet
        // die Runde nicht — nur die Funkpause tut das.
        var walks = TractiveWalkDetector.detectWalks(List.of(
                awayPoint(0), awayPoint(6), homePoint(8), awayPoint(10), awayPoint(16)), HOME);
        assertEquals(1, walks.size());
        assertEquals(16, walks.get(0).durationMinutes());
    }

    @Test
    void kurzerClusterUnterFuenfMinutenWirdVerworfen() {
        var walks = TractiveWalkDetector.detectWalks(List.of(
                awayPoint(0), awayPoint(2)), HOME);
        assertTrue(walks.isEmpty());
    }

    @Test
    void clusterVonExaktFuenfMinutenBleibtErhalten() {
        var walks = TractiveWalkDetector.detectWalks(List.of(
                awayPoint(0), awayPoint(5)), HOME);
        assertEquals(1, walks.size());
        assertEquals(5, walks.get(0).durationMinutes());
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
                awayPoint(25), awayPoint(0), awayPoint(12)), HOME);
        assertEquals(1, walks.size());
        assertEquals(25, walks.get(0).durationMinutes());
    }

    @Test
    void neuesteSpaziergaengeStehenVorn() {
        var walks = TractiveWalkDetector.detectWalks(List.of(
                awayPoint(0), awayPoint(6),
                awayPoint(120), awayPoint(130)), HOME);
        assertEquals(2, walks.size());
        assertTrue(walks.get(0).start().isAfter(walks.get(1).start()));
    }
}
