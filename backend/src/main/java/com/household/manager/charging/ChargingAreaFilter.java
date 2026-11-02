package com.household.manager.charging;

import com.household.manager.tractive.GeoZone;

import com.household.manager.charging.supercharger.SuperchargerSite;

import java.util.List;

/**
 * Schneidet das Rechteck der Quelle auf den Kreis ums Zuhause und sortiert Stationen unter
 * der Mindestleistung aus. Reine Funktion, keine Abhaengigkeiten.
 */
public final class ChargingAreaFilter {

    private ChargingAreaFilter() {
    }

    public static List<ChargingStation> filter(List<ChargingStation> stations, double homeLat, double homeLon,
                                               double radiusKm, double minPowerKw) {
        double radiusMeters = radiusKm * 1000;
        return stations.stream()
                .filter(s -> distanceMeters(homeLat, homeLon, s.lat(), s.lon()) <= radiusMeters)
                .filter(s -> minPowerKw <= 0 || (s.maxPowerKw() != null && s.maxPowerKw() >= minPowerKw))
                .toList();
    }

    /** Dieselbe Umkreis- und Mindestleistungsregel fuer Supercharger-Standorte (ohne Leistung faellt heraus). */
    public static List<SuperchargerSite> filterSites(List<SuperchargerSite> sites, double homeLat, double homeLon,
                                                     double radiusKm, double minPowerKw) {
        double radiusMeters = radiusKm * 1000;
        return sites.stream()
                .filter(s -> distanceMeters(homeLat, homeLon, s.lat(), s.lon()) <= radiusMeters)
                .filter(s -> minPowerKw <= 0 || (s.powerKw() != null && s.powerKw() >= minPowerKw))
                .toList();
    }

    /** Stationen im Rechteck (der sichtbare Kartenausschnitt, kein Kreis) ueber der Mindestleistung. */
    public static List<ChargingStation> filterInBox(List<ChargingStation> stations, BoundingBox box,
                                                    double minPowerKw) {
        return stations.stream()
                .filter(s -> inBox(box, s.lat(), s.lon()))
                .filter(s -> minPowerKw <= 0 || (s.maxPowerKw() != null && s.maxPowerKw() >= minPowerKw))
                .toList();
    }

    /** Wie {@link #filterInBox}, fuer Supercharger-Standorte (ohne Leistung faellt heraus). */
    public static List<SuperchargerSite> filterSitesInBox(List<SuperchargerSite> sites, BoundingBox box,
                                                          double minPowerKw) {
        return sites.stream()
                .filter(s -> inBox(box, s.lat(), s.lon()))
                .filter(s -> minPowerKw <= 0 || (s.powerKw() != null && s.powerKw() >= minPowerKw))
                .toList();
    }

    private static boolean inBox(BoundingBox box, double lat, double lon) {
        return lat >= box.fromLat() && lat <= box.toLat() && lon >= box.fromLon() && lon <= box.toLon();
    }

    public static double distanceMeters(double lat1, double lon1, double lat2, double lon2) {
        return GeoZone.distanceMeters(lat1, lon1, lat2, lon2);
    }
}
