package com.household.manager.communicator;

import com.fasterxml.jackson.core.JsonPointer;
import com.fasterxml.jackson.databind.JsonNode;
import com.household.manager.communicator.CommunicatorDtos.Action;
import com.household.manager.communicator.CommunicatorDtos.Block;
import com.household.manager.communicator.CommunicatorDtos.Card;
import com.household.manager.communicator.CommunicatorDtos.Item;
import com.household.manager.communicator.CommunicatorDtos.Point;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Baut aus der Beschreibung des Modells eine Karte. Das Modell nennt nur Zeiger in gelesene API-Antworten
 * ({@code ref} + JSON-Pointer), die Werte setzt diese Klasse ein. Kennt kein einzelnes Feature.
 */
@Component
@RequiredArgsConstructor
public class CardBuilder {

    private static final int MAX_BLOCKS = 6;
    private static final int MAX_ACTIONS = 4;
    private static final int MAX_ITEMS = 12;
    private static final int MAX_POINTS = 120;
    private static final int MAX_TEXT = 400;
    private static final Set<String> TONES = Set.of("good", "warn", "bad");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("EE d.M. HH:mm", Locale.GERMAN);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.GERMAN);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("EE d.M.", Locale.GERMAN);

    private final ApiCatalog catalog;
    private final Clock clock;

    /** @throws IllegalArgumentException mit einer Meldung, aus der das Modell den Fehler beheben kann */
    public Card build(JsonNode input, Map<String, JsonNode> results) {
        List<Block> blocks = new ArrayList<>();
        for (JsonNode block : input.path("blocks")) {
            if (blocks.size() < MAX_BLOCKS) {
                blocks.add(block(block, results));
            }
        }
        List<Action> actions = new ArrayList<>();
        for (JsonNode action : input.path("actions")) {
            if (actions.size() < MAX_ACTIONS) {
                actions.add(action(action));
            }
        }
        List<String> questions = new ArrayList<>();
        input.path("questions").forEach(question -> {
            if (questions.size() < MAX_ACTIONS && !question.asText().isBlank()) {
                questions.add(cut(question.asText(), 120));
            }
        });
        if (blocks.isEmpty() && actions.isEmpty()) {
            throw new IllegalArgumentException("Eine Karte braucht mindestens einen Block oder eine Aktion.");
        }
        String icon = input.path("icon").asText("");
        return new Card(cut(input.path("title").asText(""), 60),
                icon.matches("[a-z0-9_]{1,40}") ? icon : "info", blocks, actions, questions);
    }

    public static Card message(String icon, String title, String text) {
        return new Card(title, icon, List.of(Block.text(text)), List.of(), List.of());
    }

    private Block block(JsonNode block, Map<String, JsonNode> results) {
        String type = block.path("type").asText();
        String label = optional(block, "label");
        return switch (type) {
            case "text" -> Block.text(cut(block.path("text").asText(""), MAX_TEXT));
            case "value" -> Block.value(label, format(resolve(block, results), block), optional(block, "unit"),
                    TONES.contains(block.path("tone").asText()) ? block.path("tone").asText() : null);
            case "list" -> Block.list(label, items(block, array(block, results)));
            case "chart" -> Block.chart(label, optional(block, "unit"), points(block, array(block, results)));
            case "slider" -> slider(label, block, results);
            default -> throw new IllegalArgumentException("Unbekannter Baustein: " + type);
        };
    }

    private Block slider(String label, JsonNode block, Map<String, JsonNode> results) {
        JsonNode current = resolve(block, results);
        double min = block.path("min").asDouble(Double.NaN);
        double max = block.path("max").asDouble(Double.NaN);
        double step = block.path("step").asDouble(1);
        if (!(min < max) || !(step > 0)) {
            throw new IllegalArgumentException("slider braucht min < max und step > 0");
        }
        Action action = action(block.path("action"));
        if (action.body() == null || !action.body().toString().contains("\"$value\"")) {
            throw new IllegalArgumentException("slider-Aktion braucht \"$value\" im Body");
        }
        double number = current.isNumber() ? current.asDouble() : min;
        return Block.slider(label, optional(block, "unit"), Math.clamp(number, min, max), min, max, step, action);
    }

    private List<Item> items(JsonNode block, JsonNode array) {
        List<Item> items = new ArrayList<>();
        for (JsonNode entry : array) {
            if (items.size() >= MAX_ITEMS) {
                break;
            }
            items.add(new Item(field(entry, block, "itemTitle", block), field(entry, block, "itemSubtitle", block),
                    field(entry, block, "itemValue", block), optional(block, "unit")));
        }
        return items;
    }

    private String field(JsonNode entry, JsonNode block, String key, JsonNode formatting) {
        String pointer = optional(block, key);
        return pointer == null ? null : format(at(entry, pointer), formatting);
    }

    private List<Point> points(JsonNode block, JsonNode array) {
        String x = block.path("x").asText("");
        String y = block.path("y").asText("");
        List<Point> all = new ArrayList<>();
        for (JsonNode entry : array) {
            JsonNode value = at(entry, y);
            if (value.isNumber()) {
                all.add(new Point(format(at(entry, x), block), value.asDouble()));
            }
        }
        if (all.size() < 2) {
            throw new IllegalArgumentException("chart braucht mindestens zwei Zahlenwerte unter y");
        }
        int every = Math.ceilDiv(all.size(), MAX_POINTS);
        List<Point> points = new ArrayList<>();
        for (int i = 0; i < all.size(); i += every) {
            points.add(all.get(i));
        }
        return points;
    }

    private Action action(JsonNode action) {
        String method = action.path("method").asText().toUpperCase(Locale.ROOT);
        String path = action.path("path").asText();
        if ("GET".equals(method) || catalog.find(method, path).isEmpty()) {
            throw new IllegalArgumentException("Unbekannte Aktion: " + method + " " + path);
        }
        Map<String, String> query = new HashMap<>();
        action.path("query").fields().forEachRemaining(e -> query.put(e.getKey(), e.getValue().asText()));
        JsonNode body = action.path("body");
        String label = optional(action, "label");
        return new Action(cut(label == null ? "Übernehmen" : label, 40), method, path,
                query.isEmpty() ? null : query, body.isMissingNode() || body.isNull() ? null : body);
    }

    // --- Werte ------------------------------------------------------------------------------

    private static JsonNode resolve(JsonNode block, Map<String, JsonNode> results) {
        JsonNode result = results.get(block.path("ref").asText());
        if (result == null) {
            throw new IllegalArgumentException("Unbekannte ref: " + block.path("ref").asText());
        }
        JsonNode value = at(result, block.path("pointer").asText(""));
        if (value.isMissingNode()) {
            throw new IllegalArgumentException("Zeiger findet nichts: " + block.path("pointer").asText());
        }
        return value;
    }

    private static JsonNode array(JsonNode block, Map<String, JsonNode> results) {
        JsonNode value = resolve(block, results);
        if (!value.isArray()) {
            throw new IllegalArgumentException("Zeiger zeigt nicht auf eine Liste: " + block.path("pointer").asText());
        }
        return value;
    }

    private static JsonNode at(JsonNode node, String pointer) {
        String normalized = pointer.isEmpty() || pointer.startsWith("/") ? pointer : "/" + pointer;
        return node.at(JsonPointer.compile(normalized));
    }

    private String format(JsonNode value, JsonNode block) {
        if (value.isMissingNode() || value.isNull()) {
            return "–";
        }
        String mapped = optional(block.path("labels"), value.asText());
        if (mapped != null) {
            return cut(mapped, 60);
        }
        if (value.isNumber()) {
            double number = value.asDouble() * block.path("factor").asDouble(1);
            int decimals = Math.clamp(block.path("decimals").asInt(value.isIntegralNumber() ? 0 : 1), 0, 3);
            return String.format(Locale.GERMAN, "%." + decimals + "f", number);
        }
        if (value.isBoolean()) {
            return value.asBoolean() ? "Ja" : "Nein";
        }
        return cut(value.isValueNode() ? time(value.asText()) : value.toString(), 200);
    }

    /** Zeitstempel der API (UTC oder Haushaltszeit) in lokaler Kurzform; alles andere bleibt, wie es ist. */
    private String time(String text) {
        if (!text.matches("\\d{4}-\\d{2}-\\d{2}.*")) {
            return text;
        }
        try {
            if (text.length() == 10) {
                return LocalDate.parse(text).format(DATE);
            }
            LocalDateTime local = text.endsWith("Z") ? LocalDateTime.ofInstant(Instant.parse(text), clock.getZone())
                    : text.matches(".*[+-]\\d{2}:\\d{2}$")
                    ? OffsetDateTime.parse(text).atZoneSameInstant(clock.getZone()).toLocalDateTime()
                    : LocalDateTime.parse(text);
            return local.toLocalDate().equals(LocalDate.now(clock)) ? local.format(TIME) : local.format(DATE_TIME);
        } catch (DateTimeParseException ex) {
            return text;
        }
    }

    private static String optional(JsonNode node, String key) {
        String text = node.path(key).asText("");
        return text.isBlank() ? null : text;
    }

    private static String cut(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max);
    }
}
