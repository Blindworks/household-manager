package com.household.manager.blaulicht.settings;

import com.household.manager.service.ApplicationSettingsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Einzige Stelle für die fachlichen Blaulicht-Einstellungen (Kategorie {@code BLAULICHT}).
 *
 * <p>Lesen wirft nie: ein fehlender oder unlesbarer Wert fällt einzeln auf den Default zurück (mit
 * Warnung), ein DB-Fehler auf den ganzen Default-Satz — Poller und Briefing-Job laufen minütlich und
 * dürfen an einem Tippfehler nicht stehen bleiben. Speichern prüft alles und schreibt dann in einer
 * Transaktion.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BlaulichtSettingsService {

    static final String CATEGORY = "BLAULICHT";
    static final int MAX_TEXT = 100;
    static final int MAX_LIST_ENTRIES = 30;

    static final String PRESSEPORTAL_ENABLED = "presseportal_enabled";
    static final String TITLE_FILTER = "presseportal_title_filter";
    static final String NINA_ENABLED = "nina_enabled";
    static final String DASHBOARD_ARS = "nina_dashboard_ars";
    static final String LOCAL_ARS = "nina_local_ars";
    static final String EXCLUDED_PROVIDERS = "nina_excluded_providers";
    static final String MAIN = "locality_main";
    static final String DISTRICTS = "locality_districts";
    static final String NEIGHBORS = "locality_neighbors";
    static final String NIGHT_START = "night_start";
    static final String NIGHT_END = "night_end";
    static final String ADDENDUM_AT = "addendum_at";
    static final String CARD_UNTIL = "card_until";

    private final ApplicationSettingsService settings;

    public BlaulichtSettings current() {
        Map<String, String> stored;
        try {
            stored = settings.getSettingsByCategory(CATEGORY);
        } catch (RuntimeException e) {
            log.warn("Blaulicht-Einstellungen nicht lesbar, nutze Defaults: {}", e.getMessage());
            return BlaulichtSettings.defaults();
        }
        BlaulichtSettings d = BlaulichtSettings.defaults();
        Reader r = new Reader(stored);
        BlaulichtSettings.Sources sources = new BlaulichtSettings.Sources(
                r.bool(PRESSEPORTAL_ENABLED, d.sources().presseportalEnabled()),
                r.text(TITLE_FILTER, d.sources().titleFilter(), true),
                r.bool(NINA_ENABLED, d.sources().ninaEnabled()),
                r.ars(DASHBOARD_ARS, d.sources().dashboardArs()),
                r.ars(LOCAL_ARS, d.sources().localArs()),
                r.list(EXCLUDED_PROVIDERS, d.sources().excludedProviders()));
        BlaulichtSettings.Localities localities = new BlaulichtSettings.Localities(
                r.text(MAIN, d.localities().main(), false),
                r.list(DISTRICTS, d.localities().districts()),
                r.list(NEIGHBORS, d.localities().neighbors()));
        BlaulichtSettings.Briefing briefing = new BlaulichtSettings.Briefing(
                r.time(NIGHT_START, d.briefing().nightStart()),
                r.time(NIGHT_END, d.briefing().nightEnd()),
                r.time(ADDENDUM_AT, d.briefing().addendumAt()),
                r.time(CARD_UNTIL, d.briefing().cardUntil()));
        if (validateBriefing(briefing) != null) {
            log.warn("Gespeicherte Blaulicht-Uhrzeiten widersprüchlich ({}), nutze Defaults", briefing);
            briefing = d.briefing();
        }
        return new BlaulichtSettings(sources, localities, briefing);
    }

    /** Prüft und speichert den ganzen Satz; Fehler ⇒ {@link IllegalArgumentException} (400). */
    public BlaulichtSettings update(BlaulichtSettings next) {
        if (next == null || next.sources() == null || next.localities() == null || next.briefing() == null) {
            throw new IllegalArgumentException("Alle Abschnitte müssen angegeben werden.");
        }
        BlaulichtSettings.Sources s = next.sources();
        BlaulichtSettings.Localities l = next.localities();
        BlaulichtSettings.Briefing b = next.briefing();
        requireText(l.main(), "Der Hauptort darf nicht leer sein.");
        checkLength(l.main(), "Hauptort");
        checkLength(s.titleFilter(), "Titelfilter");
        if (!isArs(s.dashboardArs()) || !isArs(s.localArs())) {
            throw new IllegalArgumentException("Regionalschlüssel (ARS) müssen aus genau 12 Ziffern bestehen.");
        }
        checkList(l.districts(), "Ortsteile");
        checkList(l.neighbors(), "Nachbarorte");
        checkList(s.excludedProviders(), "Ausgeschlossene Anbieter");
        if (b.nightStart() == null || b.nightEnd() == null || b.addendumAt() == null || b.cardUntil() == null) {
            throw new IllegalArgumentException("Alle Uhrzeiten müssen angegeben werden.");
        }
        BlaulichtSettings.Briefing briefing = new BlaulichtSettings.Briefing(minute(b.nightStart()),
                minute(b.nightEnd()), minute(b.addendumAt()), minute(b.cardUntil()));
        String error = validateBriefing(briefing);
        if (error != null) {
            throw new IllegalArgumentException(error);
        }
        Map<String, String> values = new LinkedHashMap<>();
        values.put(PRESSEPORTAL_ENABLED, String.valueOf(s.presseportalEnabled()));
        values.put(TITLE_FILTER, s.titleFilter());
        values.put(NINA_ENABLED, String.valueOf(s.ninaEnabled()));
        values.put(DASHBOARD_ARS, s.dashboardArs().trim());
        values.put(LOCAL_ARS, s.localArs().trim());
        values.put(EXCLUDED_PROVIDERS, join(s.excludedProviders()));
        values.put(MAIN, l.main().trim());
        values.put(DISTRICTS, join(l.districts()));
        values.put(NEIGHBORS, join(l.neighbors()));
        values.put(NIGHT_START, briefing.nightStart().toString());
        values.put(NIGHT_END, briefing.nightEnd().toString());
        values.put(ADDENDUM_AT, briefing.addendumAt().toString());
        values.put(CARD_UNTIL, briefing.cardUntil().toString());
        settings.saveSettings(CATEGORY, values);
        return current();
    }

    /**
     * Einzige Regel für die Uhrzeiten: die Nacht läuft über Mitternacht (Beginn nach dem Ende), und am Tag
     * folgen Nachtende &lt; Nachtrag &lt; Karte-bis ≤ Nachtbeginn. {@code null} = gültig.
     */
    static String validateBriefing(BlaulichtSettings.Briefing b) {
        if (!b.nightEnd().isBefore(b.nightStart())) {
            return "Die Nacht muss über Mitternacht gehen: Nachtbeginn liegt nach dem Nachtende.";
        }
        if (!b.nightEnd().isBefore(b.addendumAt())) {
            return "Der Nachtrag muss nach dem Morgen-Briefing kommen.";
        }
        if (!b.addendumAt().isBefore(b.cardUntil())) {
            return "Die Karte muss bis nach dem Nachtrag sichtbar sein.";
        }
        if (b.cardUntil().isAfter(b.nightStart())) {
            return "Die Karte darf höchstens bis zum Nachtbeginn sichtbar sein.";
        }
        return null;
    }

    private static LocalTime minute(LocalTime time) {
        return time.truncatedTo(ChronoUnit.MINUTES);
    }

    private static boolean isArs(String value) {
        return value != null && value.trim().matches("\\d{12}");
    }

    private static void requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
    }

    private static void checkLength(String value, String label) {
        if (value != null && value.trim().length() > MAX_TEXT) {
            throw new IllegalArgumentException(label + ": höchstens " + MAX_TEXT + " Zeichen.");
        }
    }

    private static void checkList(List<String> values, String label) {
        if (values == null) {
            throw new IllegalArgumentException(label + " fehlen.");
        }
        List<String> cleaned = clean(values);
        if (cleaned.size() > MAX_LIST_ENTRIES) {
            throw new IllegalArgumentException(label + ": höchstens " + MAX_LIST_ENTRIES + " Einträge.");
        }
        for (String entry : cleaned) {
            checkLength(entry, label);
            if (entry.contains(",")) {
                throw new IllegalArgumentException(label + ": Einträge dürfen kein Komma enthalten.");
            }
        }
    }

    static List<String> clean(List<String> values) {
        return values.stream().filter(v -> v != null && !v.isBlank()).map(String::trim).distinct().toList();
    }

    private static String join(List<String> values) {
        return String.join(",", clean(values));
    }

    /** Liest einzelne Werte defensiv; jeder Fehler fällt nur für diesen Wert auf den Default. */
    private record Reader(Map<String, String> stored) {

        boolean bool(String key, boolean fallback) {
            return parse(key, fallback, v -> switch (v.trim().toLowerCase()) {
                case "true" -> true;
                case "false" -> false;
                default -> throw new IllegalArgumentException(v);
            });
        }

        String text(String key, String fallback, boolean blankAllowed) {
            String value = stored.get(key);
            if (value == null) {
                return fallback;
            }
            if (!blankAllowed && value.isBlank()) {
                log.warn("Blaulicht-Einstellung {} ist leer, nutze {}", key, fallback);
                return fallback;
            }
            return value.trim();
        }

        String ars(String key, String fallback) {
            return parse(key, fallback, v -> {
                if (!isArs(v)) {
                    throw new IllegalArgumentException(v);
                }
                return v.trim();
            });
        }

        List<String> list(String key, List<String> fallback) {
            String value = stored.get(key);
            return value == null ? fallback : clean(Arrays.asList(value.split(",")));
        }

        LocalTime time(String key, LocalTime fallback) {
            return parse(key, fallback, v -> LocalTime.parse(v.trim()));
        }

        private <T> T parse(String key, T fallback, Function<String, T> parser) {
            String value = stored.get(key);
            if (value == null) {
                return fallback;
            }
            try {
                return parser.apply(value);
            } catch (IllegalArgumentException | DateTimeParseException e) {
                log.warn("Blaulicht-Einstellung {} = '{}' unlesbar, nutze {}", key, value, fallback);
                return fallback;
            }
        }
    }
}
