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

    @Test
    void pointJustInsideRadiusIsInside() {
        // rund 50 m noerdlich (1 Breitengrad entspricht ca. 111.320 m)
        assertTrue(zone.contains(48.2082 + 0.00045, 16.3738));
    }

    @Test
    void pointOutsideRadiusIsOutside() {
        // rund 550 m noerdlich
        assertFalse(zone.contains(48.2082 + 0.005, 16.3738));
    }

    @Test
    void distanceIsSymmetric() {
        double a = GeoZone.distanceMeters(48.2082, 16.3738, 48.2100, 16.3800);
        double b = GeoZone.distanceMeters(48.2100, 16.3800, 48.2082, 16.3738);
        assertEquals(a, b, 0.001);
    }
}
