package com.household.manager.rainradar;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Liest die Antwort von {@code GET /radar?format=plain} (Bright Sky).
 *
 * <p>Je Bild liegt ein kleines Pixelraster um den angefragten Ort vor;
 * {@code latlon_position} sagt, wo der Ort darin liegt (Spalte x, Zeile y, gebrochen).
 * Werte sind 0,01 mm je 5 Minuten, also mal 0,12 fuer mm/h.
 *
 * <p>Gemessen oder Vorhersage steht in {@code source}
 * ({@code RADARCOMP::RV::<Laufzeit>}): ein Bild, dessen Zeitpunkt nach der Laufzeit
 * seines Radarlaufs liegt, ist Vorhersage. Unlesbare Bilder werden uebersprungen,
 * nie mit 0 geraten.
 */
public final class RainRadarParser {

    static final double MM_PER_HOUR_PER_UNIT = 0.01 * 12;
    private static final String SOURCE_SEPARATOR = "::";

    private RainRadarParser() {
    }

    public static List<RainRadarFrame> parse(JsonNode root) {
        JsonNode position = root.path("latlon_position");
        if (!position.path("x").isNumber() || !position.path("y").isNumber()) {
            throw new IllegalArgumentException("Radarantwort ohne latlon_position");
        }
        int column = (int) Math.floor(position.path("x").asDouble());
        int row = (int) Math.floor(position.path("y").asDouble());
        List<RainRadarFrame> frames = new ArrayList<>();
        for (JsonNode frame : root.path("radar")) {
            parseFrame(frame, row, column).ifPresent(frames::add);
        }
        return frames;
    }

    private static Optional<RainRadarFrame> parseFrame(JsonNode frame, int row, int column) {
        try {
            Instant time = OffsetDateTime.parse(frame.path("timestamp").asText()).toInstant();
            JsonNode value = frame.path("precipitation_5").path(row).path(column);
            if (!value.isNumber()) {
                return Optional.empty();
            }
            Instant run = runTime(frame.path("source").asText());
            boolean forecast = run != null && time.isAfter(run);
            return Optional.of(new RainRadarFrame(time, value.asInt() * MM_PER_HOUR_PER_UNIT, forecast));
        } catch (RuntimeException ex) {
            return Optional.empty();
        }
    }

    private static Instant runTime(String source) {
        int index = source.lastIndexOf(SOURCE_SEPARATOR);
        if (index < 0) {
            return null;
        }
        try {
            return OffsetDateTime.parse(source.substring(index + SOURCE_SEPARATOR.length())).toInstant();
        } catch (RuntimeException ex) {
            return null;
        }
    }
}
