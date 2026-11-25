package com.household.manager.communicator;

import com.household.manager.ai.AnthropicResponse;
import com.household.manager.communicator.CommunicatorDtos.Card;
import com.household.manager.communicator.CommunicatorLogDtos.Step;
import com.household.manager.model.entity.CommunicatorRequest.Status;
import lombok.Getter;

import java.util.ArrayList;
import java.util.List;

/** Sammelt, was waehrend einer Anfrage passiert. Gehoert einem Request-Thread. */
@Getter
class CommunicatorTrace {

    static final int MAX_TEXT = 4_000;
    private static final int MAX_AUDIO_MS = 120_000;

    private final long startedAt = System.nanoTime();
    private final boolean voice;
    private final Integer audioBytes;
    private final String audioType;
    private final Integer audioMs;
    private Integer speechMs;
    private String question;
    private String model;
    private Status status = Status.OK;
    private String error;
    private final List<Step> steps = new ArrayList<>();
    private final List<String> endpoints = new ArrayList<>();
    private List<Card> cards = List.of();

    private CommunicatorTrace(boolean voice, Integer audioBytes, String audioType, Integer audioMs) {
        this.voice = voice;
        this.audioBytes = audioBytes;
        this.audioType = audioType;
        this.audioMs = audioMs;
    }

    static CommunicatorTrace text() {
        return new CommunicatorTrace(false, null, null, null);
    }

    /** @param durationMs vom Browser gemessen; Unplausibles zaehlt als unbekannt */
    static CommunicatorTrace voice(long bytes, String type, Integer durationMs) {
        boolean plausible = durationMs != null && durationMs > 0 && durationMs <= MAX_AUDIO_MS;
        return new CommunicatorTrace(true, (int) Math.min(bytes, Integer.MAX_VALUE), type,
                plausible ? durationMs : null);
    }

    void question(String text) {
        question = text;
    }

    void model(String name) {
        model = name;
    }

    void speechTook(long nanos) {
        speechMs = millis(nanos);
    }

    void cards(List<Card> shown) {
        cards = List.copyOf(shown);
    }

    /** Das erste Problem zaehlt; spaetere sind meist dessen Folge. {@code OK} = Hinweis trotz Antwort. */
    void problem(Status newStatus, String message) {
        if (status == Status.OK) {
            status = newStatus;
        }
        if (error == null) {
            error = message;
        }
    }

    void claude(int round, AnthropicResponse response, long nanos) {
        steps.add(new Step(round, "CLAUDE", response.stopReason(), null, cut(response.text()), null, true,
                millis(nanos), response.inputTokens(), response.outputTokens(), response.cacheReadTokens(),
                response.cacheWriteTokens()));
    }

    void claudeFailed(int round, String message, long nanos) {
        steps.add(new Step(round, "CLAUDE", "error", null, cut(message), null, false, millis(nanos),
                null, null, null, null));
    }

    void tool(int round, String name, String input, String output, boolean ok, long nanos) {
        steps.add(new Step(round, "TOOL", name, cut(input), cut(output), output.length(), ok, millis(nanos),
                null, null, null, null));
    }

    void endpoint(String pattern) {
        endpoints.add(pattern);
    }

    int totalMs() {
        return millis(System.nanoTime() - startedAt);
    }

    private static int millis(long nanos) {
        return (int) (nanos / 1_000_000);
    }

    private static String cut(String text) {
        return text == null || text.length() <= MAX_TEXT ? text : text.substring(0, MAX_TEXT) + " …";
    }
}
