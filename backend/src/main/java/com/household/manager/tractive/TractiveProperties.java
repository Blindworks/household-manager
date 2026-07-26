package com.household.manager.tractive;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "tractive")
@Data
public class TractiveProperties {

    private boolean enabled = true;
    private String baseUrl = "https://graph.tractive.com/4";
    /** Oeffentliche Client-ID der Tractive-App; kein Geheimnis. */
    private String clientId = "625e533dc3c3b41c28a669f0";
    private long pollIntervalMs = 60000;
    private long initialDelayMs = 20000;
    private int httpTimeoutMs = 10000;

    /** Fallback-Zone, falls die Tractive-Geofences nicht lesbar sind. Radius in Metern. */
    private Double homeLatitude;
    private Double homeLongitude;
    private double homeRadiusMeters = 100;
    private String homeZoneName = "Zuhause";

    /**
     * Weiter Radius um den Home-Punkt fuer die Ausschalt-Heuristik. Der letzte
     * Positionsbericht vor dem Ausschalten stammt oft noch von unterwegs, deshalb
     * grosszuegiger als {@link #homeRadiusMeters}.
     */
    private double homeArrivalRadiusMeters = 500;

    /**
     * Ab wann ein ausbleibender Positionsbericht als "Tracker ausgeschaltet" gilt.
     * Konservativ gewaehlt: das reale Melde-Intervall im Tractive-Sparmodus ist
     * nicht verifiziert.
     */
    private long poweredOffAfterMinutes = 60;

    /**
     * Mindest-Akkustand, ab dem Stille als bewusstes Ausschalten gilt. Darunter ist
     * "Akku unterwegs leergelaufen" die wahrscheinlichere Erklaerung.
     */
    private int poweredOffMinBatteryPercent = 15;
}
