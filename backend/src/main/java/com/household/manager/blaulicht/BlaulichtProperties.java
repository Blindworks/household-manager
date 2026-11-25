package com.household.manager.blaulicht;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Technische Deploy-Konfiguration von „Blaulicht Bad Vilbel“ (Prefix {@code blaulicht}): Quell-URLs,
 * Timeouts, Höflichkeitsgrenzen. Die Poll-Intervalle stehen zusätzlich als Platzhalter an den
 * {@code @Scheduled}-Methoden. Alles Fachliche (Quellen an/aus, Orte, Uhrzeiten) liegt in der DB und wird
 * auf der Admin-Seite gepflegt ({@link com.household.manager.blaulicht.settings.BlaulichtSettingsService}).
 */
@Configuration
@ConfigurationProperties(prefix = "blaulicht")
@Data
public class BlaulichtProperties {

    /** Deploy-Schalter für das ganze Feature (Env {@code BLAULICHT_ENABLED}). */
    private boolean enabled = true;
    private String userAgent = "household-manager/1.0 (privates Haushaltsdashboard)";
    private int timeoutMs = 20_000;

    private Presseportal presseportal = new Presseportal();
    private Nina nina = new Nina();

    @Data
    public static class Presseportal {
        private String feedUrl = "https://www.presseportal.de/rss/dienststelle_154582.rss2";
        /** Höchstens so viele Detailseiten je Lauf (höflich zur Quelle). */
        private int maxDetailFetchesPerRun = 10;
    }

    @Data
    public static class Nina {
        private String baseUrl = "https://warnung.bund.de/api31";
    }
}
