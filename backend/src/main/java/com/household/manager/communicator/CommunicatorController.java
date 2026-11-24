package com.household.manager.communicator;

import com.household.manager.ai.AiProperties;
import com.household.manager.communicator.CommunicatorDtos.AskResponse;
import com.household.manager.communicator.CommunicatorDtos.AskTextRequest;
import com.household.manager.communicator.CommunicatorDtos.StatusResponse;
import com.household.manager.communicator.SpeechToTextClient.SpeechModelMissingException;
import com.household.manager.communicator.SpeechToTextClient.SpeechToTextException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

/**
 * Sprach-App „Communicator“: eine Aufnahme oder ein Text hinein, Karten heraus. Die Antwort ist immer 200
 * mit Karten — auch Stoerungen (Spracherkennung weg, nichts verstanden) kommen als Text-Karte, damit die
 * App genau einen Darstellungsweg hat. Schreiben faellt ueber {@code anyRequest} auf MEMBER.
 */
@RestController
@RequestMapping("/v1/communicator")
@RequiredArgsConstructor
@Slf4j
public class CommunicatorController {

    /** Eine halbe Minute Sprache ist unter 1 MB; alles darueber ist keine Frage mehr. */
    static final long MAX_AUDIO_BYTES = 5L * 1024 * 1024;
    static final String NOT_UNDERSTOOD_TEXT = "Ich habe nichts verstanden. Sprich nach dem Antippen etwas lauter.";

    private final CommunicatorAgentService agentService;
    private final SpeechToTextClient speechToTextClient;
    private final SpeechModelInstaller modelInstaller;
    private final CommunicatorProperties properties;
    private final AiProperties aiProperties;

    @GetMapping("/status")
    public StatusResponse status() {
        return new StatusResponse(properties.isEnabled(), aiProperties.isConfigured(),
                speechToTextClient.isConfigured());
    }

    @PostMapping(path = "/ask", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public AskResponse ask(@RequestParam("audio") MultipartFile audio, HttpServletRequest request) throws IOException {
        if (audio.isEmpty()) {
            throw new IllegalArgumentException("Die Aufnahme ist leer.");
        }
        if (audio.getSize() > MAX_AUDIO_BYTES) {
            throw new IllegalArgumentException("Die Aufnahme ist zu lang.");
        }
        String transcript;
        try {
            transcript = speechToTextClient.transcribe(audio.getBytes(), audio.getContentType());
        } catch (SpeechModelMissingException ex) {
            modelInstaller.requestInstall();
            return new AskResponse(null,
                    List.of(CardBuilder.message("downloading", "Spracherkennung", ex.getMessage())));
        } catch (SpeechToTextException ex) {
            log.warn("Spracherkennung fehlgeschlagen: {}", ex.getMessage(), ex.getCause());
            return new AskResponse(null,
                    List.of(CardBuilder.message("mic_off", "Spracherkennung", ex.getMessage())));
        }
        if (transcript.isBlank()) {
            return new AskResponse(null,
                    List.of(CardBuilder.message("hearing_disabled", "Nichts verstanden",
                            NOT_UNDERSTOOD_TEXT)));
        }
        return new AskResponse(transcript, agentService.ask(transcript, caller(request)));
    }

    @PostMapping("/ask-text")
    public AskResponse askText(@Valid @RequestBody AskTextRequest body, HttpServletRequest request) {
        String text = body.text().trim();
        return new AskResponse(text, agentService.ask(text, caller(request)));
    }

    private static ApiGateway.Caller caller(HttpServletRequest request) {
        return new ApiGateway.Caller(request.getHeader("Cookie"), request.getHeader("X-API-Token"));
    }
}
