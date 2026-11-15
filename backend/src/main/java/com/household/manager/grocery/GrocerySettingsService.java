package com.household.manager.grocery;

import com.household.manager.grocery.dto.GrocerySettingsDto;
import com.household.manager.service.ApplicationSettingsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.UUID;

/** Einstellungen der Supermarkt-Angebote (Kategorie GROCERY); Lesen wirft nie. */
@Service
@RequiredArgsConstructor
@Slf4j
public class GrocerySettingsService {

    static final String CATEGORY = "GROCERY";
    static final String DEFAULT_ZIP = "61118";
    static final double DEFAULT_LAT = 50.1781;
    static final double DEFAULT_LNG = 8.73553;
    static final int DEFAULT_THRESHOLD = 10;
    static final int MIN_THRESHOLD = 1;
    static final int MAX_THRESHOLD = 50;

    private static final String ZIP = "zip";
    private static final String LAT = "lat";
    private static final String LNG = "lng";
    private static final String THRESHOLD = "deal_threshold_percent";
    private static final String ACCOUNT_ID = "kaufda_account_id";

    private final ApplicationSettingsService settings;

    public String zip() {
        String value = read(ZIP, DEFAULT_ZIP);
        if (isValidZip(value)) {
            return value;
        }
        log.warn("Ungültige PLZ '{}' in den Einstellungen, nutze {}", value, DEFAULT_ZIP);
        return DEFAULT_ZIP;
    }

    public double lat() {
        return readCoordinate(LAT, DEFAULT_LAT, 90);
    }

    public double lng() {
        return readCoordinate(LNG, DEFAULT_LNG, 180);
    }

    public int dealThresholdPercent() {
        String value = read(THRESHOLD, String.valueOf(DEFAULT_THRESHOLD));
        try {
            int parsed = Integer.parseInt(value.trim());
            if (parsed >= MIN_THRESHOLD && parsed <= MAX_THRESHOLD) {
                return parsed;
            }
        } catch (NumberFormatException ignored) {
            // fällt auf den Default zurück
        }
        log.warn("Ungültige Schwelle '{}' in den Einstellungen, nutze {}", value, DEFAULT_THRESHOLD);
        return DEFAULT_THRESHOLD;
    }

    /** Zufällige, einmalig erzeugte Kennung für kaufDA; dieselbe Zeile nutzt {@code KaufdaSource}. */
    public String kaufdaAccountId() {
        String existing = settings.getString(CATEGORY, ACCOUNT_ID, "");
        if (existing != null && !existing.isBlank()) {
            return existing;
        }
        String generated = UUID.randomUUID().toString();
        settings.saveSetting(CATEGORY, ACCOUNT_ID, generated);
        return generated;
    }

    public boolean chainEnabled(Chain chain) {
        try {
            return settings.getBoolean(CATEGORY, chainKey(chain), true);
        } catch (RuntimeException e) {
            log.warn("Kettenschalter {} nicht lesbar: {}", chain, e.getMessage());
            return true;
        }
    }

    public void setChainEnabled(Chain chain, boolean enabled) {
        settings.saveSetting(CATEGORY, chainKey(chain), String.valueOf(enabled));
    }

    public void update(GrocerySettingsDto dto) {
        if (dto == null || dto.zip() == null || !isValidZip(dto.zip())) {
            throw new IllegalArgumentException("Die PLZ muss aus genau 5 Ziffern bestehen.");
        }
        if (!Double.isFinite(dto.lat()) || Math.abs(dto.lat()) > 90) {
            throw new IllegalArgumentException("Der Breitengrad muss zwischen -90 und 90 liegen.");
        }
        if (!Double.isFinite(dto.lng()) || Math.abs(dto.lng()) > 180) {
            throw new IllegalArgumentException("Der Längengrad muss zwischen -180 und 180 liegen.");
        }
        if (dto.dealThresholdPercent() < MIN_THRESHOLD || dto.dealThresholdPercent() > MAX_THRESHOLD) {
            throw new IllegalArgumentException("Die Schwelle muss zwischen 1 und 50 Prozent liegen.");
        }
        settings.saveSetting(CATEGORY, ZIP, dto.zip());
        settings.saveSetting(CATEGORY, LAT, String.valueOf(dto.lat()));
        settings.saveSetting(CATEGORY, LNG, String.valueOf(dto.lng()));
        settings.saveSetting(CATEGORY, THRESHOLD, String.valueOf(dto.dealThresholdPercent()));
    }

    private double readCoordinate(String key, double fallback, double limit) {
        String value = read(key, String.valueOf(fallback));
        try {
            double parsed = Double.parseDouble(value.trim());
            if (Double.isFinite(parsed) && Math.abs(parsed) <= limit) {
                return parsed;
            }
        } catch (NumberFormatException ignored) {
            // fällt auf den Default zurück
        }
        log.warn("Ungültiger Wert '{}' für {} in den Einstellungen, nutze {}", value, key, fallback);
        return fallback;
    }

    private String read(String key, String fallback) {
        try {
            String value = settings.getString(CATEGORY, key, fallback);
            return value == null ? fallback : value;
        } catch (RuntimeException e) {
            log.warn("Einstellung {} nicht lesbar: {}", key, e.getMessage());
            return fallback;
        }
    }

    private static boolean isValidZip(String zip) {
        return zip.matches("\\d{5}");
    }

    private static String chainKey(Chain chain) {
        return "chain." + chain.name() + ".enabled";
    }
}
