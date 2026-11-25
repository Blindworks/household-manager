package com.household.manager.blaulicht.settings;

import java.time.LocalTime;
import java.util.List;

/**
 * Fachliche Einstellungen von „Blaulicht Bad Vilbel“, gepflegt auf der Admin-Seite (Kategorie
 * {@code BLAULICHT} in {@code application_settings}). Technisches (Quell-URLs, Timeouts, Abrufintervalle)
 * bleibt in {@code BlaulichtProperties}. Ein Satz wird je Lauf einmal gelesen, damit eine Auswertung
 * konsistente Werte sieht.
 */
public record BlaulichtSettings(Sources sources, Localities localities, Briefing briefing) {

    /** Quellen: an/aus und ihre regionalen Filter. */
    public record Sources(boolean presseportalEnabled, String titleFilter,
                          boolean ninaEnabled, String dashboardArs, String localArs,
                          List<String> excludedProviders) {
        public Sources {
            titleFilter = titleFilter == null ? "" : titleFilter.trim();
            excludedProviders = List.copyOf(excludedProviders);
        }
    }

    /** Hauptort, Ortsteile (gleiche Relevanz wie der Hauptort) und Nachbarorte (niedrige Relevanz). */
    public record Localities(String main, List<String> districts, List<String> neighbors) {
        public Localities {
            districts = List.copyOf(districts);
            neighbors = List.copyOf(neighbors);
        }
    }

    /**
     * Nacht [nightStart, nightEnd) über Mitternacht; das Morgen-Briefing kommt zu {@code nightEnd}, der
     * Nachtrag zu {@code addendumAt}, die Hub-Karte steht von {@code nightEnd} bis {@code cardUntil}.
     */
    public record Briefing(LocalTime nightStart, LocalTime nightEnd, LocalTime addendumAt, LocalTime cardUntil) {
    }

    public static BlaulichtSettings defaults() {
        return new BlaulichtSettings(
                new Sources(true, "WETTERAUKREIS", true, "064400000000", "064400003003", List.of("dwd")),
                new Localities("Bad Vilbel", List.of("Heilsberg", "Massenheim", "Dortelweil", "Gronau"),
                        List.of("Karben", "Niederdorfelden", "Schöneck", "Frankfurt-Berkersheim", "Berkersheim",
                                "Frankfurt-Harheim", "Harheim", "Frankfurt-Nieder-Erlenbach", "Nieder-Erlenbach",
                                "Frankfurt-Bergen-Enkheim")),
                new Briefing(LocalTime.of(20, 0), LocalTime.of(8, 0), LocalTime.of(12, 0), LocalTime.of(20, 0)));
    }
}
