package com.household.manager.tractive;

/**
 * Kreisfoermige Zone mit Mittelpunkt und Radius in Metern.
 * Die Distanzberechnung nutzt die Haversine-Formel; fuer Zonengroessen
 * im Meter- bis Kilometerbereich ist die Kugelnaeherung ausreichend genau.
 */
public record GeoZone(String name, double latitude, double longitude, double radiusMeters) {

    private static final double EARTH_RADIUS_METERS = 6_371_000d;

    /** Der Rand zaehlt als innerhalb. */
    public boolean contains(double pointLatitude, double pointLongitude) {
        return distanceMeters(latitude, longitude, pointLatitude, pointLongitude) <= radiusMeters;
    }

    public static double distanceMeters(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * EARTH_RADIUS_METERS * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }
}
