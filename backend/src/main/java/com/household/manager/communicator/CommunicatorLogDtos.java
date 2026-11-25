package com.household.manager.communicator;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.household.manager.model.entity.CommunicatorRequest.Status;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** API-Vertrag des Communicator-Protokolls (Admin). */
public final class CommunicatorLogDtos {

    private CommunicatorLogDtos() {
    }

    public record Row(Long id, LocalDateTime askedAt, String actor, boolean voice, Status status, String question,
                      String error, String model, Integer audioBytes, String audioType, Integer audioMs,
                      Integer speechMs, int totalMs, int aiCalls, int toolCalls, int toolFailures, int inputTokens,
                      int outputTokens, int cacheReadTokens, int cacheWriteTokens,
                      @JsonIgnore String endpoints) {

        @JsonProperty
        public boolean problem() {
            return status != Status.OK || error != null || toolFailures > 0;
        }
    }

    public record RowPage(List<Row> content, long totalElements, int page, int size) {
    }

    /** {@code details}: {@code steps} (Verlauf) und {@code cards} (gezeigte Antwort); null, wenn nicht lesbar. */
    public record Detail(Row row, JsonNode details) {
    }

    /**
     * Ein Schritt des Verlaufs: ein Claude-Aufruf ({@code CLAUDE}, mit Tokens) oder ein Werkzeug ({@code TOOL}).
     * {@code outputChars} ist die ungekuerzte Laenge dessen, was an Claude ging.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Step(int round, String type, String name, String input, String output, Integer outputChars,
                       boolean ok, long ms, Integer inputTokens, Integer outputTokens, Integer cacheReadTokens,
                       Integer cacheWriteTokens) {
    }

    public record Tokens(long input, long output, long cacheRead, long cacheWrite) {
    }

    public record Day(LocalDate date, int requests, int problems, Tokens tokens) {
    }

    public record Count(String name, long count) {
    }

    /** Zeiten in Millisekunden; null ohne Messung. {@code realtimeFactor}: Rechenzeit je Sekunde Aufnahme. */
    public record SpeechStats(int recordings, int notUnderstood, int failures, Integer medianMs, Integer maxMs,
                              Double realtimeFactor) {
    }

    public record Summary(int requests, int problems, Map<Status, Long> byStatus, Tokens tokens,
                          Integer medianTotalMs, Integer maxTotalMs, double averageAiCalls, int toolCalls,
                          int toolFailures, SpeechStats speech, List<Day> days, List<Count> endpoints,
                          List<Count> errors) {
    }

    /** Laufende Einrichtung und frische Pruefung des Whisper-Dienstes. */
    public record Health(boolean enabled, boolean aiConfigured, String aiModel, int endpoints, int catalogChars,
                         SpeechHealth speech) {
    }

    public record SpeechHealth(boolean configured, String model, String language, boolean reachable,
                               Boolean modelInstalled, Integer responseMs, String error, boolean installing,
                               String installError) {
    }
}
