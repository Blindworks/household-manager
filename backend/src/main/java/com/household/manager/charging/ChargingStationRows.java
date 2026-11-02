package com.household.manager.charging;

import com.household.manager.charging.supercharger.SuperchargerSite;

import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Die einzige Zeilenbildung fuer Umkreis- und Supercharger-Stationen: Stationsliste und
 * Bereichssuche fragen dieselben Methoden, damit beide Antworten nicht auseinanderlaufen.
 */
final class ChargingStationRows {

    private ChargingStationRows() {
    }

    static ChargingDtos.StationResponse area(ChargingStation s, double homeLat, double homeLon, boolean favorite) {
        long distance = Math.round(ChargingAreaFilter.distanceMeters(homeLat, homeLon, s.lat(), s.lon()));
        return new ChargingDtos.StationResponse(s.stationId(), s.name(), s.operator(), s.address(), s.lat(), s.lon(),
                distance, s.maxPowerKw(), null, null, s.total(), s.free(), favorite, null, null, null, null);
    }

    /** Supercharger kennen nur den Standort: keine Belegung, kein Preis, keine Ladepunkte, kein Favorit. */
    static ChargingDtos.StationResponse supercharger(SuperchargerSite s, double homeLat, double homeLon) {
        long distance = Math.round(ChargingAreaFilter.distanceMeters(homeLat, homeLon, s.lat(), s.lon()));
        return new ChargingDtos.StationResponse(ChargingQueryService.SUPERCHARGER_ID_PREFIX + s.id(),
                "Tesla Supercharger " + place(s),
                "Tesla", address(s), s.lat(), s.lon(), distance,
                s.powerKw() == null ? null : s.powerKw().doubleValue(), null, null, s.stallCount(), 0, false, null,
                null, true, s.otherEvs() ? null : Boolean.TRUE);
    }

    /** Ort aus „Pfungstadt, Germany“ (ohne Laender-Suffix); Rueckfall auf die Stadt der Adresse. */
    private static String place(SuperchargerSite s) {
        String name = s.name();
        if (name != null && !name.isBlank()) {
            int comma = name.lastIndexOf(", ");
            return comma > 0 ? name.substring(0, comma) : name;
        }
        return s.city();
    }

    private static String address(SuperchargerSite s) {
        return Stream.of(s.street(), s.city()).filter(p -> p != null && !p.isBlank())
                .collect(Collectors.joining(", "));
    }
}
