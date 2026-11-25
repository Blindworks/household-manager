package com.household.manager.blaulicht;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Deploy-Konfiguration von „Blaulicht Bad Vilbel“ (Prefix {@code blaulicht}).
 * Die Poll-Intervalle stehen zusätzlich als Platzhalter an den {@code @Scheduled}-Methoden.
 */
@Configuration
@ConfigurationProperties(prefix = "blaulicht")
@Data
public class BlaulichtProperties {

    private boolean enabled = true;
    private String userAgent = "household-manager/1.0 (privates Haushaltsdashboard)";
    private int timeoutMs = 20_000;

    private Presseportal presseportal = new Presseportal();
    private Nina nina = new Nina();
    private Localities localities = new Localities();
    private Briefing briefing = new Briefing();

    @Data
    public static class Presseportal {
        private boolean enabled = true;
        private String feedUrl = "https://www.presseportal.de/rss/dienststelle_154582.rss2";
        /** Nur Meldungen, deren Titel diesen Text enthält (die Dienststelle deckt ganz Mittelhessen ab). Leer = alle. */
        private String titleFilter = "WETTERAUKREIS";
        /** Höchstens so viele Detailseiten je Lauf (höflich zur Quelle). */
        private int maxDetailFetchesPerRun = 10;
    }

    @Data
    public static class Nina {
        private boolean enabled = true;
        private String baseUrl = "https://warnung.bund.de/api31";
        /** ARS auf Kreisebene für das Dashboard (Wetteraukreis). */
        private String dashboardArs = "064400000000";
        /** ARS der Gemeinde; eine Warnung betrifft Bad Vilbel, wenn sie ihn, den Kreis oder das Land nennt. */
        private String localArs = "064400003003";
        /** Anbieter (Präfix der Warnungs-Id), die ignoriert werden; DWD-Wetter hat das Dashboard schon. */
        private List<String> excludedProviders = new ArrayList<>(List.of("dwd"));
    }

    @Data
    public static class Localities {
        /** Hauptort; Ortsteile werden im Text dieser Meldungen gesucht. */
        private String main = "Bad Vilbel";
        private List<String> districts = new ArrayList<>(List.of("Heilsberg", "Massenheim", "Dortelweil", "Gronau"));
        /** Nachbarorte mit niedriger Relevanz. */
        private List<String> neighbors = new ArrayList<>(List.of("Karben", "Niederdorfelden", "Schöneck",
                "Frankfurt-Berkersheim", "Berkersheim", "Frankfurt-Harheim", "Harheim",
                "Frankfurt-Nieder-Erlenbach", "Nieder-Erlenbach", "Frankfurt-Bergen-Enkheim"));
        private int mainRelevance = 100;
        private int neighborRelevance = 30;
    }

    @Data
    public static class Briefing {
        private LocalTime nightStart = LocalTime.of(20, 0);
        private LocalTime nightEnd = LocalTime.of(7, 0);
        /** Ab hier zeigt der Hub zusätzlich den Nachtrag. */
        private LocalTime addendumAt = LocalTime.of(12, 0);
        /** Bis hier zeigt der Hub die Karte (danach beginnt die nächste Nacht). */
        private LocalTime cardUntil = LocalTime.of(20, 0);
    }
}
