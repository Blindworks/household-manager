package com.household.manager.tractive;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Bestimmt aus einer Position den Zonennamen. Bekannte Zonen gewinnen; ist keine
 * bekannt, greift die konfigurierte Home-Zone. Ohne beides bleibt der Zustand
 * {@code unknown} – ein Zonenzustand wird nie geraten, damit Geofence-Flows
 * nicht auf erfundenen Werten feuern.
 */
@Component
@RequiredArgsConstructor
public class TractiveZoneResolver {

    /** Zustand ausserhalb aller bekannten Zonen. */
    public static final String AWAY = "away";
    /** Zustand, wenn keine Zonenaussage moeglich ist. */
    public static final String UNKNOWN = "unknown";

    private final TractiveHomeSettingsService settingsService;

    public String resolve(double latitude, double longitude, List<GeoZone> zones) {
        List<GeoZone> effectiveZones = zones.isEmpty() ? homeZone() : zones;
        if (effectiveZones.isEmpty()) {
            return UNKNOWN;
        }
        return effectiveZones.stream()
                .filter(zone -> zone.contains(latitude, longitude))
                .map(GeoZone::name)
                .findFirst()
                .orElse(AWAY);
    }

    /** Wird nur ohne bekannte Geofences gebraucht – kostet sonst keine Abfrage. */
    private List<GeoZone> homeZone() {
        TractiveHomeSettings settings = settingsService.getSettings();
        if (!settings.hasHomeCoordinates()) {
            return List.of();
        }
        return List.of(new GeoZone(settings.homeZoneName(),
                settings.homeLatitude(), settings.homeLongitude(),
                settings.homeRadiusMeters()));
    }
}
