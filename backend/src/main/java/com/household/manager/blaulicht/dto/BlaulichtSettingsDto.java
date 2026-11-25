package com.household.manager.blaulicht.dto;

import com.household.manager.blaulicht.settings.BlaulichtSettings;

import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.List;

/** Admin-Seite „Blaulicht“: alle fachlichen Einstellungen in einem Satz, Uhrzeiten als {@code HH:mm}. */
public record BlaulichtSettingsDto(
        boolean presseportalEnabled,
        String titleFilter,
        boolean ninaEnabled,
        String dashboardArs,
        String localArs,
        List<String> excludedProviders,
        String mainLocality,
        List<String> districts,
        List<String> neighbors,
        String nightStart,
        String nightEnd,
        String addendumAt,
        String cardUntil) {

    public static BlaulichtSettingsDto from(BlaulichtSettings s) {
        return new BlaulichtSettingsDto(
                s.sources().presseportalEnabled(), s.sources().titleFilter(),
                s.sources().ninaEnabled(), s.sources().dashboardArs(), s.sources().localArs(),
                s.sources().excludedProviders(),
                s.localities().main(), s.localities().districts(), s.localities().neighbors(),
                s.briefing().nightStart().toString(), s.briefing().nightEnd().toString(),
                s.briefing().addendumAt().toString(), s.briefing().cardUntil().toString());
    }

    /** Fehlende Listen gelten als leer; eine unlesbare Uhrzeit ist ein 400. */
    public BlaulichtSettings toSettings() {
        return new BlaulichtSettings(
                new BlaulichtSettings.Sources(presseportalEnabled, titleFilter, ninaEnabled,
                        dashboardArs, localArs, orEmpty(excludedProviders)),
                new BlaulichtSettings.Localities(mainLocality, orEmpty(districts), orEmpty(neighbors)),
                new BlaulichtSettings.Briefing(time(nightStart, "Nachtbeginn"), time(nightEnd, "Nachtende"),
                        time(addendumAt, "Nachtrag"), time(cardUntil, "Karte sichtbar bis")));
    }

    private static List<String> orEmpty(List<String> values) {
        return values == null ? List.of() : values.stream().filter(v -> v != null).toList();
    }

    private static LocalTime time(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + ": Uhrzeit fehlt.");
        }
        try {
            return LocalTime.parse(value.trim());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(label + ": Uhrzeit im Format HH:mm angeben.");
        }
    }
}
