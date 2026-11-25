package com.household.manager.communicator;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.household.manager.ai.ClaudeModels;
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

        /** Geschaetzt aus den heutigen Listenpreisen; null bei unbekanntem Modell. */
        @JsonProperty
        public Double costUsd() {
            return ClaudeModels.find(model)
                    .map(m -> m.cost(inputTokens, outputTokens, cacheReadTokens, cacheWriteTokens)).orElse(null);
        }

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

    public record Day(LocalDate date, int requests, int problems, Tokens tokens, double costUsd) {
    }

    public record ModelCost(String model, int requests, double costUsd) {
    }

    /**
     * {@code monthUsd}: Schnitt je Kalendertag seit der ersten Anfrage im Fenster, mal 30. {@code unpriced}:
     * Anfragen mit Tokens, aber ohne bekannten Preis; sie fehlen in der Summe.
     */
    public record Cost(double totalUsd, double todayUsd, Double monthUsd, Double perRequestUsd, int unpriced,
                       List<ModelCost> models) {
    }

    public record ModelChoice(String id, String label, double inputPrice, double cacheWritePrice,
                              double cacheReadPrice, double outputPrice) {
    }

    public record ModelSettings(String current, List<ModelChoice> models) {
    }

    public record ModelUpdate(String model) {
    }

    public record Count(String name, long count) {
    }

    /** Zeiten in Millisekunden; null ohne Messung. {@code realtimeFactor}: Rechenzeit je Sekunde Aufnahme. */
    public record SpeechStats(int recordings, int notUnderstood, int failures, Integer medianMs, Integer maxMs,
                              Double realtimeFactor) {
    }

    public record Summary(int requests, int problems, Map<Status, Long> byStatus, Tokens tokens, Cost cost,
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
