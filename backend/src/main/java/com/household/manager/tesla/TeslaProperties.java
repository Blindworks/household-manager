package com.household.manager.tesla;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Technische Einstellungen der Tessie-Anbindung. Token, Fahrzeug und Abrufintervall
 * sind Nutzereinstellungen und liegen in der Datenbank ({@link TeslaSettingsService}).
 */
@Configuration
@ConfigurationProperties(prefix = "tesla")
@Data
public class TeslaProperties {

    /** Deploy-Schalter: {@code false} schaltet Abruf und Befehle ab. */
    private boolean enabled = true;
    private String baseUrl = "https://api.tessie.com";
    /** Lesen (Fahrzeugliste, Zustand). */
    private int readTimeoutMs = 20000;
    /**
     * Befehle warten auf die Ausführung im Auto ({@code wait_for_completion}), das
     * schließt ein Aufwecken ein. Unter dem nginx-Limit von 60 s.
     */
    private int commandTimeoutMs = 45000;
}
