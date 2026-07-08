package com.household.manager.alexa;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Konfiguration der Alexa-Integration. */
@Component
@ConfigurationProperties(prefix = "alexa")
@Getter
@Setter
public class AlexaProperties {

    /** Amazon-Domain des Kontos, z. B. amazon.de. */
    private String domain = "amazon.de";

    /** Konfiguration des Browser-Login-Proxys. */
    private Proxy proxy = new Proxy();

    /** Einstellungen des eingebetteten Login-Proxys, ueber den sich der Nutzer im Browser anmeldet. */
    @Getter
    @Setter
    public static class Proxy {

        /** Host/IP, unter der der Browser den Proxy erreicht (lokaler Login: localhost). */
        private String host = "localhost";

        /** Port des eingebetteten Proxy-Servers. */
        private int port = 8091;
    }
}
