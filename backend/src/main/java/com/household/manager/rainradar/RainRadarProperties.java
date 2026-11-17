package com.household.manager.rainradar;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Deploy-Konfiguration des Regenradars (Bright Sky, DWD-Radarkomposit RV). */
@Configuration
@ConfigurationProperties(prefix = "rain-radar")
@Data
public class RainRadarProperties {

    private boolean enabled = true;
    private String baseUrl = "https://api.brightsky.dev/radar";
    private int timeoutMs = 15000;
    /** Ab dieser Intensitaet (mm/h) gilt es als Regen; darunter liegen Nieselreste und Radarrauschen. */
    private double thresholdMmPerHour = 0.3;
    /** „Regen bald“: Regen jetzt oder in der Vorhersage innerhalb dieser Minuten. */
    private int soonMinutes = 15;
    /** Aelter darf die juengste Messung nicht sein, sonst gilt der Abruf als gescheitert. */
    private int maxMeasurementAgeMinutes = 30;
    /** So viele Fehlabrufe in Folge, bevor die Entitaeten {@code unavailable} werden. */
    private int failuresBeforeUnavailable = 3;
}
