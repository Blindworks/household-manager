package com.household.manager.communicator;

import com.household.manager.ai.AiProperties;
import com.household.manager.communicator.CommunicatorDtos.AskResponse;
import com.household.manager.communicator.CommunicatorDtos.AskTextRequest;
import com.household.manager.communicator.CommunicatorDtos.Card;
import com.household.manager.communicator.CommunicatorDtos.StatusResponse;
import com.household.manager.communicator.SpeechToTextClient.SpeechModelMissingException;
import com.household.manager.communicator.SpeechToTextClient.SpeechToTextException;
import com.household.manager.model.entity.CommunicatorRequest.Status;
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
    private final CommunicatorLogService logService;
    private final SpeechToTextClient speechToTextClient;
    private final SpeechModelInstaller modelInstaller;
    private final CommunicatorProperties properties;
    private final AiProperties aiProperties;

    @GetMapping("/status")
    public StatusResponse status() {
        return new StatusResponse(properties.isEnabled(), aiProperties.isConfigured(),
                speechToTextClient.isConfigured());
    }

    /** @param durationMs Laenge der Aufnahme laut Browser, nur fuers Protokoll */
    @PostMapping(path = "/ask", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public AskResponse ask(@RequestParam("audio") MultipartFile audio,
                           @RequestParam(name = "durationMs", required = false) Integer durationMs,
                           HttpServletRequest request) throws IOException {
        CommunicatorTrace trace = CommunicatorTrace.voice(audio.getSize(), audio.getContentType(), durationMs);
        try {
            return answer(audio, request, trace);
        } finally {
            logService.record(trace);
        }
    }

    private AskResponse answer(MultipartFile audio, HttpServletRequest request, CommunicatorTrace trace)
            throws IOException {
        if (audio.isEmpty()) {
            throw rejected("Die Aufnahme ist leer.", trace);
        }
        if (audio.getSize() > MAX_AUDIO_BYTES) {
            throw rejected("Die Aufnahme ist zu lang.", trace);
        }
        String transcript;
        long start = System.nanoTime();
        try {
            transcript = speechToTextClient.transcribe(audio.getBytes(), audio.getContentType());
        } catch (SpeechModelMissingException ex) {
            modelInstaller.requestInstall();
            return failed("downloading", "Spracherkennung", Status.MODEL_MISSING, ex, trace);
        } catch (SpeechToTextException ex) {
            log.warn("Spracherkennung fehlgeschlagen: {}", ex.getMessage(), ex.getCause());
            return failed("mic_off", "Spracherkennung", Status.SPEECH_ERROR, ex, trace);
        } finally {
            trace.speechTook(System.nanoTime() - start);
        }
        if (transcript.isBlank()) {
            return failed("hearing_disabled", "Nichts verstanden", Status.NOT_UNDERSTOOD,
                    new SpeechToTextException(NOT_UNDERSTOOD_TEXT, null), trace);
        }
        return new AskResponse(transcript, agentService.ask(transcript, caller(request), trace));
    }

    @PostMapping("/ask-text")
    public AskResponse askText(@Valid @RequestBody AskTextRequest body, HttpServletRequest request) {
        String text = body.text().trim();
        CommunicatorTrace trace = CommunicatorTrace.text();
        try {
            return new AskResponse(text, agentService.ask(text, caller(request), trace));
        } finally {
            logService.record(trace);
        }
    }

    private static IllegalArgumentException rejected(String message, CommunicatorTrace trace) {
        trace.problem(Status.REJECTED, message);
        return new IllegalArgumentException(message);
    }

    /** Der Nutzer sieht die Meldung, das Protokoll zusaetzlich die Ursache. */
    private static AskResponse failed(String icon, String title, Status status, SpeechToTextException ex,
                                      CommunicatorTrace trace) {
        trace.problem(status, ex.getCause() == null ? ex.getMessage()
                : ex.getMessage() + " " + ex.getCause().getMessage());
        List<Card> cards = List.of(CardBuilder.message(icon, title, ex.getMessage()));
        trace.cards(cards);
        return new AskResponse(null, cards);
    }

    private static ApiGateway.Caller caller(HttpServletRequest request) {
        return new ApiGateway.Caller(request.getHeader("Cookie"), request.getHeader("X-API-Token"));
    }
}
