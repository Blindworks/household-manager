package com.household.manager.tractive;

import com.household.manager.audit.AuditService;
import com.household.manager.service.ApplicationSettingsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Uebersetzt zwischen {@link TractiveHomeSettings} und den String-Werten in
 * {@code application_settings}.
 *
 * <p>Kein Lesevorgang wirft: der Poller laeuft jede Minute, und ein Tippfehler in der
 * Datenbank darf ihn nicht lahmlegen. Unlesbare oder unplausible Werte fallen auf den
 * Default zurueck und werden geloggt. Die Controller-Validierung verhindert solche Werte
 * beim Speichern – ein direkter DB-Zugriff umgeht sie aber.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TractiveHomeSettingsService {

    static final String CATEGORY = "TRACTIVE_HOME";

    static final String KEY_LATITUDE = "home_latitude";
    static final String KEY_LONGITUDE = "home_longitude";
    static final String KEY_RADIUS = "home_radius_meters";
    static final String KEY_ARRIVAL_RADIUS = "home_arrival_radius_meters";
    static final String KEY_POWERED_OFF_AFTER = "powered_off_after_minutes";
    static final String KEY_MIN_BATTERY = "powered_off_min_battery_percent";
    static final String KEY_ZONE_NAME = "home_zone_name";

    static final double DEFAULT_RADIUS_METERS = 100;
    static final double DEFAULT_ARRIVAL_RADIUS_METERS = 500;
    static final long DEFAULT_POWERED_OFF_AFTER_MINUTES = 60;
    static final int DEFAULT_MIN_BATTERY_PERCENT = 15;
    static final String DEFAULT_ZONE_NAME = "Zuhause";

    /**
     * Obergrenze fuer beide Radien. Ein per Hand in die DB geschriebener Ausreisser
     * (fehlendes Komma, Meter/Kilometer verwechselt) wuerde die halbe Welt zum Zuhause
     * machen und die Entitaet dauerhaft auf "zu Hause" einfrieren – die unsichere
     * Richtung fuer einen darauf gebauten Alarm.
     */
    static final double MAX_RADIUS_METERS = 100_000;

    private final ApplicationSettingsService applicationSettings;
    private final AuditService auditService;

    /**
     * Liest die ganze Kategorie in einer Abfrage. Aufrufer sollen das Ergebnis einmal holen
     * und damit weiterrechnen, damit eine Bewertung einen konsistenten Satz Werte sieht.
     */
    public TractiveHomeSettings getSettings() {
        Map<String, String> values = applicationSettings.getSettingsByCategory(CATEGORY);

        Double latitude = coordinate(values.get(KEY_LATITUDE), KEY_LATITUDE, 90);
        Double longitude = coordinate(values.get(KEY_LONGITUDE), KEY_LONGITUDE, 180);
        // Eine halbe Koordinate ist keine Position.
        if (latitude == null || longitude == null) {
            latitude = null;
            longitude = null;
        }

        return new TractiveHomeSettings(
                latitude,
                longitude,
                positiveDouble(values.get(KEY_RADIUS), KEY_RADIUS, DEFAULT_RADIUS_METERS),
                positiveDouble(values.get(KEY_ARRIVAL_RADIUS), KEY_ARRIVAL_RADIUS,
                        DEFAULT_ARRIVAL_RADIUS_METERS),
                positiveLong(values.get(KEY_POWERED_OFF_AFTER), KEY_POWERED_OFF_AFTER,
                        DEFAULT_POWERED_OFF_AFTER_MINUTES),
                percent(values.get(KEY_MIN_BATTERY), KEY_MIN_BATTERY, DEFAULT_MIN_BATTERY_PERCENT),
                zoneName(values.get(KEY_ZONE_NAME)));
    }

    /**
     * Ein einziger {@code saveSettings}-Aufruf, damit ein Fehler mitten drin keine halb
     * aktualisierte Konfiguration hinterlaesst.
     */
    public void saveSettings(TractiveHomeSettings settings) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put(KEY_LATITUDE, text(settings.homeLatitude()));
        values.put(KEY_LONGITUDE, text(settings.homeLongitude()));
        values.put(KEY_RADIUS, String.valueOf(settings.homeRadiusMeters()));
        values.put(KEY_ARRIVAL_RADIUS, String.valueOf(settings.homeArrivalRadiusMeters()));
        values.put(KEY_POWERED_OFF_AFTER, String.valueOf(settings.poweredOffAfterMinutes()));
        values.put(KEY_MIN_BATTERY, String.valueOf(settings.poweredOffMinBatteryPercent()));
        values.put(KEY_ZONE_NAME, settings.homeZoneName() == null || settings.homeZoneName().isBlank()
                ? DEFAULT_ZONE_NAME : settings.homeZoneName());

        applicationSettings.saveSettings(CATEGORY, values);
        // Wer verschiebt, was "zu Hause" heisst, aendert das Verhalten jedes darauf
        // gebauten Flows – das gehoert nachvollziehbar protokolliert.
        auditService.record("tractive.home-settings.update",
                settings.hasHomeCoordinates()
                        ? settings.homeLatitude() + ", " + settings.homeLongitude()
                        : "Koordinaten entfernt");
        log.info("Tractive-Home-Einstellungen gespeichert");
    }

    /** Leere Koordinate wird als leerer String abgelegt – die Spalte ist NOT NULL. */
    private String text(Double value) {
        return value == null ? "" : String.valueOf(value);
    }

    /** {@code null} heisst "nicht konfiguriert". */
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

    private double positiveDouble(String raw, String key, double defaultValue) {
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            double value = Double.parseDouble(raw);
            if (!Double.isFinite(value) || value <= 0 || value > MAX_RADIUS_METERS) {
                log.warn("Unplausibler Wert '{}' fuer {}, nutze {}", raw, key, defaultValue);
                return defaultValue;
            }
            return value;
        } catch (NumberFormatException ex) {
            log.warn("Unlesbarer Wert '{}' fuer {}, nutze {}", raw, key, defaultValue);
            return defaultValue;
        }
    }

    private long positiveLong(String raw, String key, long defaultValue) {
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            long value = Long.parseLong(raw);
            if (value <= 0) {
                log.warn("Unplausibler Wert '{}' fuer {}, nutze {}", raw, key, defaultValue);
                return defaultValue;
            }
            return value;
        } catch (NumberFormatException ex) {
            log.warn("Unlesbarer Wert '{}' fuer {}, nutze {}", raw, key, defaultValue);
            return defaultValue;
        }
    }

    private int percent(String raw, String key, int defaultValue) {
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            int value = Integer.parseInt(raw);
            if (value < 0 || value > 100) {
                log.warn("Unplausibler Wert '{}' fuer {}, nutze {}", raw, key, defaultValue);
                return defaultValue;
            }
            return value;
        } catch (NumberFormatException ex) {
            log.warn("Unlesbarer Wert '{}' fuer {}, nutze {}", raw, key, defaultValue);
            return defaultValue;
        }
    }

    private String zoneName(String raw) {
        return raw == null || raw.isBlank() ? DEFAULT_ZONE_NAME : raw;
    }
}
