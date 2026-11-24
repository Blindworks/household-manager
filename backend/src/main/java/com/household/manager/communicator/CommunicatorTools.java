package com.household.manager.communicator;

import java.util.List;
import java.util.Map;

/** Die drei allgemeinen Werkzeuge des Communicators. Bewusst keines je Thema. */
final class CommunicatorTools {

    static final String API_GET = "api_get";
    static final String API_DESCRIBE = "api_describe";
    static final String SHOW_CARD = "show_card";

    private static final Map<String, Object> ACTION = Map.of(
            "type", "object",
            "properties", Map.of(
                    "label", string("Knopftext: was mit welchem Ziel passiert"),
                    "method", Map.of("type", "string", "enum", List.of("POST", "PUT", "DELETE", "PATCH")),
                    "path", string("konkreter Pfad ohne Platzhalter"),
                    "query", Map.of("type", "object"),
                    "body", Map.of("description", "JSON-Body; bei slider \"$value\" als Platzhalter fuer die Zahl")),
            "required", List.of("label", "method", "path"));

    private static final Map<String, Object> BLOCK = Map.of(
            "type", "object",
            "properties", Map.ofEntries(
                    Map.entry("type", Map.of("type", "string",
                            "enum", List.of("text", "value", "list", "chart", "slider"))),
                    Map.entry("text", string("nur text: hoechstens zwei kurze Saetze")),
                    Map.entry("label", string("kurze Beschriftung")),
                    Map.entry("ref", string("Id der api_get-Antwort, z. B. r1")),
                    Map.entry("pointer", string("JSON-Pointer in diese Antwort; list/chart: auf eine Liste")),
                    Map.entry("unit", string("Einheit, z. B. °C")),
                    Map.entry("decimals", Map.of("type", "integer")),
                    Map.entry("factor", Map.of("type", "number", "description", "Umrechnung, z. B. 0.001 m -> km")),
                    Map.entry("labels", Map.of("type", "object",
                            "description", "Rohwert -> Anzeige, z. B. {\"on\":\"An\"}")),
                    Map.entry("tone", Map.of("type", "string", "enum", List.of("good", "warn", "bad"))),
                    Map.entry("itemTitle", string("list: Pointer im Eintrag")),
                    Map.entry("itemSubtitle", string("list: Pointer im Eintrag")),
                    Map.entry("itemValue", string("list: Pointer im Eintrag")),
                    Map.entry("x", string("chart: Pointer im Eintrag, z. B. /time")),
                    Map.entry("y", string("chart: Pointer im Eintrag auf eine Zahl")),
                    Map.entry("min", Map.of("type", "number")),
                    Map.entry("max", Map.of("type", "number")),
                    Map.entry("step", Map.of("type", "number")),
                    Map.entry("action", ACTION)),
            "required", List.of("type"));

    static final List<Map<String, Object>> ALL = List.of(
            tool(API_GET, "Liest einen GET-Endpunkt mit den Rechten des Nutzers.", Map.of(
                    "path", string("konkreter Pfad, z. B. /v1/thermostats"),
                    "query", Map.of("type", "object"),
                    "filter", string("behaelt bei Listen nur Eintraege, die diesen Text enthalten")),
                    List.of("path")),
            tool(API_DESCRIBE, "Beschreibt Parameter, Body und Antwort eines Endpunkts mit Typen.", Map.of(
                    "method", string("z. B. PUT"), "path", string("Pfad aus dem Verzeichnis")),
                    List.of("method", "path")),
            tool(SHOW_CARD, "Zeigt dem Nutzer eine Karte.", Map.of(
                    "title", string("kurzer Titel"),
                    "icon", string("Material-Symbols-Name"),
                    "blocks", Map.of("type", "array", "items", BLOCK),
                    "actions", Map.of("type", "array", "items", ACTION),
                    "questions", Map.of("type", "array", "items", Map.of("type", "string"),
                            "description", "Folgefragen als Knoepfe")),
                    List.of("title", "blocks")));

    private CommunicatorTools() {
    }

    private static Map<String, Object> string(String description) {
        return Map.of("type", "string", "description", description);
    }

    private static Map<String, Object> tool(String name, String description, Map<String, Object> properties,
                                            List<String> required) {
        return Map.of("name", name, "description", description,
                "input_schema", Map.of("type", "object", "properties", properties, "required", required));
    }
}
