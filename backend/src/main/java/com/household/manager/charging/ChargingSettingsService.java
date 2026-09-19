package com.household.manager.charging;

import com.household.manager.audit.AuditService;
import com.household.manager.service.ApplicationSettingsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Uebersetzt zwischen {@link ChargingSettings} und den String-Werten in application_settings.
 * Lesen wirft nie: der Poller laeuft jede Minute, ein Tippfehler in der DB darf ihn nicht
 * lahmlegen (Muster TractiveHomeSettingsService).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ChargingSettingsService {

    static final String CATEGORY = "CHARGING";
    static final String KEY_LAT = "home_lat";
    static final String KEY_LON = "home_lon";
    static final String KEY_RADIUS_KM = "radius_km";
    static final String KEY_MIN_POWER_KW = "min_power_kw";

    static final double DEFAULT_RADIUS_KM = 10.0;
    static final double DEFAULT_MIN_POWER_KW = 50.0;
    public static final double MIN_RADIUS_KM = 1.0;
    public static final double MAX_RADIUS_KM = 50.0;
    public static final double MIN_POWER_KW = 0.0;
    public static final double MAX_POWER_KW = 400.0;

    private final ApplicationSettingsService applicationSettings;
    private final AuditService auditService;

    public ChargingSettings getSettings() {
        Map<String, String> values = applicationSettings.getSettingsByCategory(CATEGORY);
        Double lat = coordinate(values.get(KEY_LAT), KEY_LAT, 90);
        Double lon = coordinate(values.get(KEY_LON), KEY_LON, 180);
        if (lat == null || lon == null) {
            lat = null;
            lon = null;
        }
        return new ChargingSettings(lat, lon,
                bounded(values.get(KEY_RADIUS_KM), KEY_RADIUS_KM, MIN_RADIUS_KM, MAX_RADIUS_KM, DEFAULT_RADIUS_KM),
                bounded(values.get(KEY_MIN_POWER_KW), KEY_MIN_POWER_KW, MIN_POWER_KW, MAX_POWER_KW, DEFAULT_MIN_POWER_KW));
    }

    public void saveSettings(ChargingSettings settings) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put(KEY_LAT, settings.homeLatitude() == null ? "" : String.valueOf(settings.homeLatitude()));
        values.put(KEY_LON, settings.homeLongitude() == null ? "" : String.valueOf(settings.homeLongitude()));
        values.put(KEY_RADIUS_KM, String.valueOf(settings.radiusKm()));
        values.put(KEY_MIN_POWER_KW, String.valueOf(settings.minPowerKw()));
        applicationSettings.saveSettings(CATEGORY, values);
        auditService.record("charging.settings.update", settings.isConfigured()
                ? settings.homeLatitude() + ", " + settings.homeLongitude() + ", " + settings.radiusKm()
                + " km, ab " + settings.minPowerKw() + " kW"
                : "Koordinaten entfernt");
        log.info("Ladesaeulen-Einstellungen gespeichert");
    }

    private Double coordinate(String raw, String key, double limit) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            double value = Double.parseDouble(raw);
            if (!Double.isFinite(value) || Math.abs(value) > limit) {
                log.warn("Unplausibler Wert '{}' fuer {}, wird ignoriert", raw, key);
                return null;
            }
            return value;
        } catch (NumberFormatException ex) {
            log.warn("Unlesbarer Wert '{}' fuer {}, wird ignoriert", raw, key);
            return null;
        }
    }

    private double bounded(String raw, String key, double min, double max, double defaultValue) {
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            double value = Double.parseDouble(raw);
            if (!Double.isFinite(value) || value < min || value > max) {
                log.warn("Unplausibler Wert '{}' fuer {}, nutze {}", raw, key, defaultValue);
                return defaultValue;
            }
            return value;
        } catch (NumberFormatException ex) {
            log.warn("Unlesbarer Wert '{}' fuer {}, nutze {}", raw, key, defaultValue);
            return defaultValue;
        }
    }
}
