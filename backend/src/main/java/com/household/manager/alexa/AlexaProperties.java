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

    /** Konfiguration des alexa-remote2-Sidecars (Login + Alexa-Kommunikation). */
    private Sidecar sidecar = new Sidecar();

    /** Der Node-Sidecar uebernimmt Login, Geraeteliste und TTS; das Backend ruft ihn per HTTP auf. */
    @Getter
    @Setter
    public static class Sidecar {

        /** Basis-URL des Sidecar-Dienstes. */
        private String baseUrl = "http://localhost:3456";
    }
}
