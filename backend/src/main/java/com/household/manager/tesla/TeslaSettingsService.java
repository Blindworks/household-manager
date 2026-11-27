package com.household.manager.tesla;

import com.household.manager.audit.AuditService;
import com.household.manager.service.ApplicationSettingsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Tessie-Token, Fahrzeug und Abrufintervall in {@code application_settings}, Kategorie TESLA.
 * Der Token liegt im Klartext (LAN-Trade-off wie Dirigera/Tractive) und verlässt das
 * Backend nie. Lesen wirft nie: der Poller läuft jede Minute.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TeslaSettingsService {

    static final String CATEGORY = "TESLA";
    static final String KEY_TOKEN = "api_token";
    static final String KEY_VIN = "vin";
    static final String KEY_INTERVAL = "poll_interval_minutes";
    static final String KEY_IMAGE_URL = "image_url";
    static final int MAX_IMAGE_URL_LENGTH = 2000;

    public static final int DEFAULT_INTERVAL_MINUTES = 5;
    public static final int MIN_INTERVAL_MINUTES = 1;
    public static final int MAX_INTERVAL_MINUTES = 60;
    static final int MAX_TOKEN_LENGTH = 500;
    private static final Pattern VIN_PATTERN = Pattern.compile("[A-HJ-NPR-Z0-9]{17}");

    private final ApplicationSettingsService applicationSettings;
    private final AuditService auditService;

    public TeslaSettings current() {
        Map<String, String> raw;
        try {
            raw = applicationSettings.getSettingsByCategory(CATEGORY);
        } catch (Exception ex) {
            log.warn("Tesla-Einstellungen nicht lesbar: {}", ex.getMessage());
            return new TeslaSettings(null, null, DEFAULT_INTERVAL_MINUTES);
        }
        String token = blankToNull(raw.get(KEY_TOKEN));
        String vin = blankToNull(raw.get(KEY_VIN));
        return new TeslaSettings(token, vin, parseInterval(raw.get(KEY_INTERVAL)), parseImageUrl(raw.get(KEY_IMAGE_URL)));
    }

    /**
     * Speichert die Einstellungen.
     *
     * @param apiToken {@code null} = Token unverändert lassen, leer = Token entfernen
     * @param imageUrl leer = Bild automatisch aus Teslas Konfigurator
     */
    public TeslaSettings update(String apiToken, String vin, int pollIntervalMinutes, String imageUrl) {
        if (pollIntervalMinutes < MIN_INTERVAL_MINUTES || pollIntervalMinutes > MAX_INTERVAL_MINUTES) {
            throw new IllegalArgumentException("Das Abrufintervall muss zwischen " + MIN_INTERVAL_MINUTES
                    + " und " + MAX_INTERVAL_MINUTES + " Minuten liegen.");
        }
        String normalizedVin = blankToNull(vin);
        if (normalizedVin != null) {
            normalizedVin = normalizedVin.trim().toUpperCase(java.util.Locale.ROOT);
            if (!VIN_PATTERN.matcher(normalizedVin).matches()) {
                throw new IllegalArgumentException("Die Fahrgestellnummer muss 17 Zeichen lang sein.");
            }
        }
        String normalizedImageUrl = blankToNull(imageUrl);
        if (normalizedImageUrl != null && !isValidImageUrl(normalizedImageUrl)) {
            throw new IllegalArgumentException("Die Bildadresse muss mit https:// beginnen und darf höchstens "
                    + MAX_IMAGE_URL_LENGTH + " Zeichen lang sein.");
        }
        Map<String, String> values = new HashMap<>();
        values.put(KEY_IMAGE_URL, normalizedImageUrl == null ? "" : normalizedImageUrl);
        values.put(KEY_VIN, normalizedVin == null ? "" : normalizedVin);
        values.put(KEY_INTERVAL, String.valueOf(pollIntervalMinutes));
        String tokenChange = "unverändert";
        if (apiToken != null) {
            String trimmed = apiToken.trim();
            if (trimmed.length() > MAX_TOKEN_LENGTH) {
                throw new IllegalArgumentException("Der Token ist zu lang.");
            }
            values.put(KEY_TOKEN, trimmed);
            tokenChange = trimmed.isEmpty() ? "entfernt" : "neu";
        }
        applicationSettings.saveSettings(CATEGORY, values);
        auditService.record("tesla.settings.update", "vin=" + (normalizedVin == null ? "auto" : normalizedVin)
                + ", intervall=" + pollIntervalMinutes + " min, token=" + tokenChange
                + ", bild=" + (normalizedImageUrl == null ? "auto" : "eigene Adresse"));
        return current();
    }

    private static int parseInterval(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT_INTERVAL_MINUTES;
        }
        try {
            int value = Integer.parseInt(raw.trim());
            if (value >= MIN_INTERVAL_MINUTES && value <= MAX_INTERVAL_MINUTES) {
                return value;
            }
        } catch (NumberFormatException ignored) {
            // fällt auf den Default zurück
        }
        log.warn("Ungültiges Tesla-Abrufintervall '{}', nutze {}", raw, DEFAULT_INTERVAL_MINUTES);
        return DEFAULT_INTERVAL_MINUTES;
    }

    private static String parseImageUrl(String raw) {
        String value = blankToNull(raw);
        if (value == null) {
            return null;
        }
        if (!isValidImageUrl(value)) {
            log.warn("Ungültige Tesla-Bildadresse in den Einstellungen, nutze das Konfiguratorbild");
            return null;
        }
        return value;
    }

    /** Nur https und eine echte Hostangabe; das Backend lädt die Adresse selbst (nur ADMIN darf sie setzen). */
    static boolean isValidImageUrl(String value) {
        if (value.length() > MAX_IMAGE_URL_LENGTH) {
            return false;
        }
        try {
            java.net.URI uri = java.net.URI.create(value);
            return "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null && !uri.getHost().isBlank();
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
