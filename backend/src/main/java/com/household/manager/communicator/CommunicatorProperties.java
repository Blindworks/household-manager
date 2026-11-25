package com.household.manager.communicator;

import lombok.Data;
import lombok.ToString;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Konfiguration des Communicators (Sprach-App). Der Anthropic-Schluessel liegt in {@code AiProperties};
 * hier steht nur, was der Communicator zusaetzlich braucht: Modell und Spracherkennung.
 */
@Configuration
@ConfigurationProperties(prefix = "communicator")
@Data
public class CommunicatorProperties {

    private boolean enabled = true;
    private String model = "claude-sonnet-4-5";
    /** Neuere Modelle denken vor der Antwort nach; das zaehlt mit. */
    private int maxTokens = 8000;
    /** Obergrenze der Tool-Runden je Frage (Endlosschleifen-Schutz und Antwortzeit). */
    private int maxToolIterations = 8;
    private Stt stt = new Stt();

    /** Spracherkennung ueber einen OpenAI-kompatiblen Dienst ({@code POST /v1/audio/transcriptions}). */
    @Data
    public static class Stt {
        /** Leer = Spracherkennung nicht eingerichtet; Texteingabe funktioniert trotzdem. */
        private String baseUrl = "";
        @ToString.Exclude
        private String apiKey = "";
        private String model = "whisper-1";
        private String language = "de";
        private int timeoutMs = 30000;
        /**
         * Lokaler speaches-Server: ein fehlendes Modell beim Start und nach einem 404 selbst nachladen
         * ({@code POST /v1/models/<modell>}). Cloud-APIs kennen den Endpunkt nicht, deshalb Default aus.
         */
        private boolean autoInstallModel = false;

        public boolean isConfigured() {
            return baseUrl != null && !baseUrl.isBlank();
        }
    }
}
