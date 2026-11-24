package com.household.manager.communicator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.web.client.RestTemplateBuilder;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Laeuft nur von Hand gegen einen echten speaches-Server:
 * {@code WHISPER_LIVE_URL=http://127.0.0.1:8009 WHISPER_LIVE_AUDIO=frage.wav mvn test -Dtest=SpeechToTextLiveTest}.
 * Prueft den ganzen Weg des Clients: Modell nachladen, Aufnahme senden, Text lesen.
 */
@EnabledIfEnvironmentVariable(named = "WHISPER_LIVE_URL", matches = ".+")
class SpeechToTextLiveTest {

    @Test
    void laedtDasModellUndErkenntDieAufnahme() throws Exception {
        CommunicatorProperties properties = new CommunicatorProperties();
        properties.getStt().setBaseUrl(System.getenv("WHISPER_LIVE_URL"));
        properties.getStt().setModel("Systran/faster-whisper-small");
        SpeechToTextClient client = new SpeechToTextClient(properties, new RestTemplateBuilder());
        Path audio = Path.of(System.getenv("WHISPER_LIVE_AUDIO"));

        client.installModel();
        long start = System.nanoTime();
        String text = client.transcribe(Files.readAllBytes(audio), "audio/wav");
        long millis = (System.nanoTime() - start) / 1_000_000;

        System.out.println("Erkannt in " + millis + " ms: " + text);
        assertTrue(text.toLowerCase().contains("temperatur"), text);
    }
}
