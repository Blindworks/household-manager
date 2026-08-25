package com.household.manager.presence;

import com.household.manager.audit.AuditService;
import com.household.manager.service.ApplicationSettingsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Karenzzeit der Anwesenheitserkennung in {@code application_settings}
 * (Kategorie PRESENCE). Lesen wirft nie: der Poller laeuft alle 30 s, ein
 * Tippfehler in der Datenbank darf ihn nicht lahmlegen (Muster
 * {@code TractiveHomeSettingsService}).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PresenceSettingsService {

    static final String CATEGORY = "PRESENCE";
    static final String KEY_AWAY_GRACE = "away_grace_minutes";
    static final long DEFAULT_AWAY_GRACE_MINUTES = 10;
    /** Mehr als 24 h Karenz ergibt keine Abwesenheitserkennung mehr. */
    static final long MAX_AWAY_GRACE_MINUTES = 1440;

    private final ApplicationSettingsService applicationSettings;
    private final AuditService auditService;

    public long getAwayGraceMinutes() {
        String raw;
        try {
            raw = applicationSettings.getSettingsByCategory(CATEGORY).get(KEY_AWAY_GRACE);
        } catch (Exception ex) {
            log.warn("Karenzzeit konnte nicht gelesen werden, nutze {}", DEFAULT_AWAY_GRACE_MINUTES, ex);
            return DEFAULT_AWAY_GRACE_MINUTES;
        }
        if (raw == null || raw.isBlank()) {
            return DEFAULT_AWAY_GRACE_MINUTES;
        }
        try {
            long value = Long.parseLong(raw);
            if (value < 1 || value > MAX_AWAY_GRACE_MINUTES) {
                log.warn("Unplausibler Wert '{}' fuer {}, nutze {}", raw, KEY_AWAY_GRACE,
                        DEFAULT_AWAY_GRACE_MINUTES);
                return DEFAULT_AWAY_GRACE_MINUTES;
            }
            return value;
        } catch (NumberFormatException ex) {
            log.warn("Unlesbarer Wert '{}' fuer {}, nutze {}", raw, KEY_AWAY_GRACE,
                    DEFAULT_AWAY_GRACE_MINUTES);
            return DEFAULT_AWAY_GRACE_MINUTES;
        }
    }

    /**
     * Persistiert ohne eigene Bereichspruefung — die Validierung ist Aufgabe der
     * API-Grenze (Controller); ein direkter Aufruf umgeht sie (Muster
     * {@code TractiveHomeSettingsService}).
     */
    public void saveAwayGraceMinutes(long minutes) {
        applicationSettings.saveSettings(CATEGORY, Map.of(KEY_AWAY_GRACE, String.valueOf(minutes)));
        auditService.record("presence.settings.update", KEY_AWAY_GRACE + "=" + minutes);
    }
}
