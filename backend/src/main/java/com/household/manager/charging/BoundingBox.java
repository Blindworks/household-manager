package com.household.manager.charging;

/**
 * Rechteck fuer die Umkreis-Suche. Die Quelle kennt nur Rechtecke; der Kreis-Schnitt
 * passiert danach in {@link ChargingAreaFilter}.
 */
public record BoundingBox(double fromLat, double toLat, double fromLon, double toLon) {

    private static final double KM_PER_DEGREE_LAT = 111.32;

    /** Umschliessendes Rechteck eines Kreises; die Laengengrad-Spanne haengt vom Breitengrad ab. */
    public static BoundingBox around(double lat, double lon, double radiusKm) {
        double dLat = radiusKm / KM_PER_DEGREE_LAT;
        double kmPerDegreeLon = KM_PER_DEGREE_LAT * Math.cos(Math.toRadians(lat));
        double dLon = radiusKm / Math.max(kmPerDegreeLon, 0.001);
        return new BoundingBox(lat - dLat, lat + dLat, lon - dLon, lon + dLon);
    }
}
