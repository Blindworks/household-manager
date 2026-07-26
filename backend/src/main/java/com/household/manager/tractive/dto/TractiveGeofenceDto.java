package com.household.manager.tractive.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.household.manager.tractive.GeoZone;

import java.util.List;
import java.util.Optional;

/**
 * Virtual Fence aus {@code GET /tracker/{trackerId}/geofences}.
 * Alle Felder sind optional: die API ist inoffiziell, und nur kreisfoermige
 * Zonen werden ausgewertet – alles andere wird bewusst ignoriert statt geraten.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TractiveGeofenceDto(
        String name,
        Boolean active,
        Shape shape
) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Shape(String type, List<Double> center, Double radius) {
    }

    /** Wandelt in eine {@link GeoZone}, sofern es eine nutzbare aktive Kreiszone ist. */
    public Optional<GeoZone> toZone() {
        if (Boolean.FALSE.equals(active) || shape == null || shape.radius() == null) {
            return Optional.empty();
        }
        // Fehlender Typ wird akzeptiert: die echte Antwortform ist unbestaetigt, ein
        // unbekanntes Feld darf die Zonen nicht komplett abschalten.
        String type = shape.type();
        if (type != null && !"circle".equalsIgnoreCase(type)) {
            return Optional.empty();
        }
        List<Double> center = shape.center();
        if (center == null || center.size() < 2 || center.get(0) == null || center.get(1) == null) {
            return Optional.empty();
        }
        double radius = shape.radius();
        double latitude = center.get(0);
        double longitude = center.get(1);
        if (!Double.isFinite(radius) || radius <= 0
                || !Double.isFinite(latitude) || !Double.isFinite(longitude)
                || Math.abs(latitude) > 90 || Math.abs(longitude) > 180) {
            return Optional.empty();
        }
        String zoneName = name != null && !name.isBlank() ? name : "Zone";
        return Optional.of(new GeoZone(zoneName, latitude, longitude, radius));
    }
}
