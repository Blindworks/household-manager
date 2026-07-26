package com.household.manager.tractive;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GeoZoneTest {

    /** Wiener Stephansdom als Zonenmittelpunkt, Radius 100 m. */
    private final GeoZone zone = new GeoZone("Zuhause", 48.2082, 16.3738, 100);

    @Test
    void pointAtCenterIsInside() {
        assertTrue(zone.contains(48.2082, 16.3738));
    }

    /** Ein Grad Breite entspricht bei R = 6.371 km rund 111.195 m. */
    private static final double DEGREES_PER_METER = 1 / 111_194.93;

    @Test
    void pointWellInsideRadiusIsInside() {
        assertTrue(zone.contains(48.2082 + 50 * DEGREES_PER_METER, 16.3738));
    }

    @Test
    void pointJustInsideRadiusIsInside() {
        assertTrue(zone.contains(48.2082 + 99.9 * DEGREES_PER_METER, 16.3738));
    }

    @Test
    void pointJustOutsideRadiusIsOutside() {
        assertFalse(zone.contains(48.2082 + 100.1 * DEGREES_PER_METER, 16.3738));
    }

    /** Genau auf dem Rand: der Radius ist exakt die gemessene Distanz. */
    @Test
    void pointExactlyOnTheBoundaryIsInside() {
        double latitude = 48.2090;
        double distance = GeoZone.distanceMeters(48.2082, 16.3738, latitude, 16.3738);
        GeoZone exact = new GeoZone("Rand", 48.2082, 16.3738, distance);

        assertTrue(exact.contains(latitude, 16.3738));
    }

    @Test
    void distanceIsSymmetric() {
        double a = GeoZone.distanceMeters(48.2082, 16.3738, 48.2100, 16.3800);
        double b = GeoZone.distanceMeters(48.2100, 16.3800, 48.2082, 16.3738);
        assertEquals(a, b, 0.001);
    }
}
