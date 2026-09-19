package com.household.manager.charging;

import com.household.manager.tractive.GeoZone;

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

    public static double distanceMeters(double lat1, double lon1, double lat2, double lon2) {
        return GeoZone.distanceMeters(lat1, lon1, lat2, lon2);
    }
}
