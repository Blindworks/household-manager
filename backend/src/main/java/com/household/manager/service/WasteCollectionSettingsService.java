package com.household.manager.service;

import com.household.manager.dto.WasteCollectionSettings;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.List;

/**
 * Uebersetzt zwischen dem typisierten {@link WasteCollectionSettings} und den String-Werten
 * in {@code application_settings}. Kapselt zugleich die defensive Auslegung fehlerhafter
 * Werte, damit ein Tippfehler in der DB die Scheduler nicht lahmlegt.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WasteCollectionSettingsService {

    static final String CATEGORY = "WASTE_COLLECTION";

    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_ICS_URL = "ics_url";
    private static final String KEY_LOOKAHEAD_DAYS = "lookahead_days";
    private static final String KEY_REMINDER_ENABLED = "reminder_enabled";
    private static final String KEY_REMINDER_TIME = "reminder_time";
    private static final String KEY_REMINDER_SERIALS = "reminder_alexa_serials";
    private static final String KEY_LAST_ANNOUNCED = "last_announced_date";

    private static final LocalTime DEFAULT_REMINDER_TIME = LocalTime.of(19, 0);
    private static final String DEFAULT_REMINDER_TIME_TEXT = "19:00";
    private static final int DEFAULT_LOOKAHEAD_DAYS = 3;

    private final ApplicationSettingsService settingsService;

    public WasteCollectionSettings getSettings() {
        return WasteCollectionSettings.builder()
                .enabled(isEnabled())
                .icsUrl(getIcsUrl())
                .lookaheadDays(getLookaheadDays())
                .reminderEnabled(isReminderEnabled())
                .reminderTime(settingsService.getString(
                        CATEGORY, KEY_REMINDER_TIME, DEFAULT_REMINDER_TIME_TEXT))
                .reminderAlexaSerials(getReminderAlexaSerials())
                .build();
    }

    /** Schreibt die vom Nutzer pflegbaren Werte; {@code last_announced_date} bleibt unberuehrt. */
    public void saveSettings(WasteCollectionSettings settings) {
        settingsService.saveSetting(CATEGORY, KEY_ENABLED, String.valueOf(settings.isEnabled()));
        settingsService.saveSetting(CATEGORY, KEY_ICS_URL, nullToEmpty(settings.getIcsUrl()));
        settingsService.saveSetting(CATEGORY, KEY_LOOKAHEAD_DAYS,
                String.valueOf(settings.getLookaheadDays()));
        settingsService.saveSetting(CATEGORY, KEY_REMINDER_ENABLED,
                String.valueOf(settings.isReminderEnabled()));
        settingsService.saveSetting(CATEGORY, KEY_REMINDER_TIME,
                nullToEmpty(settings.getReminderTime()));
        settingsService.saveSetting(CATEGORY, KEY_REMINDER_SERIALS,
                settings.getReminderAlexaSerials() == null
                        ? "" : String.join(",", settings.getReminderAlexaSerials()));
        log.info("Muellabfuhr-Einstellungen gespeichert");
    }

    public boolean isEnabled() {
        return settingsService.getBoolean(CATEGORY, KEY_ENABLED, false);
    }

    public boolean isReminderEnabled() {
        return settingsService.getBoolean(CATEGORY, KEY_REMINDER_ENABLED, true);
    }

    public String getIcsUrl() {
        return settingsService.getString(CATEGORY, KEY_ICS_URL, "");
    }

    /** Nie kleiner als 1 — ein Fenster von 0 Tagen wuerde die Kachel dauerhaft leeren. */
    public int getLookaheadDays() {
        return Math.max(1, settingsService.getInt(CATEGORY, KEY_LOOKAHEAD_DAYS, DEFAULT_LOOKAHEAD_DAYS));
    }

    /** Faellt bei unparsbarem Wert auf 19:00 zurueck, statt den Scheduler scheitern zu lassen. */
    public LocalTime getReminderTime() {
        String raw = settingsService.getString(CATEGORY, KEY_REMINDER_TIME, DEFAULT_REMINDER_TIME_TEXT);
        try {
            return LocalTime.parse(raw);
        } catch (DateTimeParseException ex) {
            log.warn("Ungueltige Ansagezeit '{}', nutze {}", raw, DEFAULT_REMINDER_TIME);
            return DEFAULT_REMINDER_TIME;
        }
    }

    public List<String> getReminderAlexaSerials() {
        String raw = settingsService.getString(CATEGORY, KEY_REMINDER_SERIALS, "");
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    public void markAnnounced(LocalDate date) {
        settingsService.saveSetting(CATEGORY, KEY_LAST_ANNOUNCED, date.toString());
    }

    public boolean wasAnnouncedOn(LocalDate date) {
        return date.toString().equals(settingsService.getString(CATEGORY, KEY_LAST_ANNOUNCED, ""));
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
