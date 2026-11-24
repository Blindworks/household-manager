package com.household.manager.communicator;

import com.household.manager.communicator.SpeechToTextClient.SpeechModelMissingException;
import com.household.manager.communicator.SpeechToTextClient.SpeechToTextException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class SpeechToTextClientTest {

    private CommunicatorProperties properties;
    private SpeechToTextClient client;
    private MockRestServiceServer server;
    private MockRestServiceServer installServer;

    @BeforeEach
    void setUp() {
        properties = new CommunicatorProperties();
        properties.getStt().setBaseUrl("http://whisper:8000/");
        properties.getStt().setModel("Systran/faster-whisper-small");
        client = new SpeechToTextClient(properties, new RestTemplateBuilder());
        server = MockRestServiceServer.createServer(client.restTemplate());
        installServer = MockRestServiceServer.createServer(client.installRestTemplate());
    }

    @Test
    void liefertDenErkanntenText() {
        server.expect(requestTo("http://whisper:8000/v1/audio/transcriptions"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"text\":\" Wie warm ist es? \"}", MediaType.APPLICATION_JSON));

        assertEquals("Wie warm ist es?", client.transcribe(new byte[]{1, 2, 3}, "audio/mp4"));
        server.verify();
    }

    @Test
    void sendetDenSchluesselNurWennEinerGesetztIst() {
        properties.getStt().setApiKey("geheim");
        client = new SpeechToTextClient(properties, new RestTemplateBuilder());
        server = MockRestServiceServer.createServer(client.restTemplate());
        server.expect(requestTo("http://whisper:8000/v1/audio/transcriptions"))
                .andExpect(header("Authorization", "Bearer geheim"))
                .andRespond(withSuccess("{\"text\":\"ja\"}", MediaType.APPLICATION_JSON));

        assertEquals("ja", client.transcribe(new byte[]{1}, "audio/webm"));
    }

    @Test
    void fehlendesModellIstEinEigenerFehler() {
        server.expect(requestTo("http://whisper:8000/v1/audio/transcriptions"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND).body("{\"detail\":\"not installed\"}"));

        SpeechModelMissingException ex = assertThrows(SpeechModelMissingException.class,
                () -> client.transcribe(new byte[]{1}, "audio/webm"));
        assertEquals(SpeechToTextClient.MODEL_MISSING_TEXT, ex.getMessage());
    }

    @Test
    void andereFehlerSindNichtErreichbar() {
        server.expect(requestTo("http://whisper:8000/v1/audio/transcriptions"))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        SpeechToTextException ex = assertThrows(SpeechToTextException.class,
                () -> client.transcribe(new byte[]{1}, "audio/webm"));
        assertFalse(ex instanceof SpeechModelMissingException);
    }

    @Test
    void ohneBasisUrlWirdNichtsGesendet() {
        properties.getStt().setBaseUrl("");
        client = new SpeechToTextClient(properties, new RestTemplateBuilder());

        assertFalse(client.isConfigured());
        assertThrows(SpeechToTextException.class, () -> client.transcribe(new byte[]{1}, "audio/webm"));
    }

    @Test
    void modellInstallationTraegtDieIdUnveraendertImPfad() {
        installServer.expect(requestTo("http://whisper:8000/v1/models/Systran/faster-whisper-small"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("Model downloaded", MediaType.TEXT_PLAIN));

        client.installModel();
        installServer.verify();
    }

    @Test
    void dateinameFolgtDemAufnahmeformat() {
        assertEquals("audio.m4a", SpeechToTextClient.fileNameFor("audio/mp4"));
        assertEquals("audio.m4a", SpeechToTextClient.fileNameFor("audio/mp4;codecs=mp4a.40.2"));
        assertEquals("audio.webm", SpeechToTextClient.fileNameFor("audio/webm;codecs=opus"));
        assertEquals("audio.ogg", SpeechToTextClient.fileNameFor("audio/ogg"));
        assertEquals("audio.wav", SpeechToTextClient.fileNameFor("audio/wav"));
        assertEquals("audio.webm", SpeechToTextClient.fileNameFor(null));
    }
}
