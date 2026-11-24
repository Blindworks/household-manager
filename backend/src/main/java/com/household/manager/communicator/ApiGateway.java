package com.household.manager.communicator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;

/**
 * Liest die eigene API mit der Anmeldung des fragenden Nutzers (Cookie wird durchgereicht). Der Aufruf laeuft
 * bewusst als echte HTTP-Anfrage: so gelten dieselben Sicherheitsregeln wie fuer die Website, ohne zweite
 * Rechteliste. Nur GET und nur Pfade aus dem {@link ApiCatalog}; Aenderungen fuehrt ausschliesslich der Tipp
 * des Nutzers im Browser aus.
 */
@Component
public class ApiGateway {

    static final int MAX_RESULT_CHARS = 12_000;
    private static final Duration TIMEOUT = Duration.ofSeconds(15);
    private static final String SERVICE_TOKEN_HEADER = "X-API-Token";

    private final ApiCatalog catalog;
    private final ObjectMapper objectMapper;
    private final Environment environment;
    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(5)).build();

    public ApiGateway(ApiCatalog catalog, ObjectMapper objectMapper, Environment environment) {
        this.catalog = catalog;
        this.objectMapper = objectMapper;
        this.environment = environment;
    }

    /** Anmeldung des fragenden Nutzers, wie sie mit seiner Anfrage kam. */
    public record Caller(String cookie, String serviceToken) {
        @Override
        public String toString() {
            return "Caller[***]";
        }
    }

    /** {@code data} ist genau das, was das Modell sieht; Zeiger auf Karten beziehen sich darauf. */
    public record Result(JsonNode data, int total, int shown) {
        public boolean shortened() {
            return shown < total;
        }
    }

    /**
     * @param filter behaelt bei Listen nur Eintraege, deren JSON den Text enthaelt (ohne Gross-/Kleinschreibung)
     * @throws IllegalArgumentException unbekannter Pfad oder abgelehnter Aufruf (Meldung fuers Modell)
     */
    public Result get(String path, Map<String, String> query, String filter, Caller caller) {
        if (catalog.find("GET", path).isEmpty()) {
            throw new IllegalArgumentException("Kein lesbarer Endpunkt: " + path);
        }
        return shorten(filtered(parse(send(uri(path, query), caller)), filter));
    }

    URI uri(String path, Map<String, String> query) {
        String port = environment.getProperty("local.server.port", environment.getProperty("server.port", "8080"));
        String context = environment.getProperty("server.servlet.context-path", "");
        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString("http://127.0.0.1:" + port)
                .path(context + path);
        query.forEach((name, value) -> builder.queryParam("{" + name + "}", "{v_" + name + "}"));
        Map<String, Object> variables = new java.util.HashMap<>();
        query.forEach((name, value) -> {
            variables.put(name, name);
            variables.put("v_" + name, value);
        });
        return builder.encode().buildAndExpand(variables).toUri();
    }

    private String send(URI uri, Caller caller) {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri).timeout(TIMEOUT).GET()
                .header(HttpHeaders.ACCEPT, "application/json");
        if (caller.cookie() != null) {
            request.header(HttpHeaders.COOKIE, caller.cookie());
        }
        if (caller.serviceToken() != null) {
            request.header(SERVICE_TOKEN_HEADER, caller.serviceToken());
        }
        try {
            HttpResponse<String> response = http.send(request.build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() >= 300) {
                throw new IllegalArgumentException("HTTP " + response.statusCode() + ": " + message(response.body()));
            }
            return response.body();
        } catch (IOException ex) {
            throw new IllegalArgumentException("Aufruf fehlgeschlagen: " + ex.getMessage(), ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalArgumentException("Aufruf abgebrochen", ex);
        }
    }

    private String message(String body) {
        String text = body == null ? "" : body;
        return text.length() <= 300 ? text : text.substring(0, 300);
    }

    private JsonNode parse(String body) {
        try {
            return body == null || body.isBlank() ? objectMapper.nullNode() : objectMapper.readTree(body);
        } catch (IOException ex) {
            throw new IllegalArgumentException("Antwort ist kein JSON", ex);
        }
    }

    JsonNode filtered(JsonNode data, String filter) {
        if (filter == null || filter.isBlank() || !data.isArray()) {
            return data;
        }
        String needle = filter.toLowerCase(Locale.ROOT);
        ArrayNode result = objectMapper.createArrayNode();
        data.forEach(item -> {
            if (item.toString().toLowerCase(Locale.ROOT).contains(needle)) {
                result.add(item);
            }
        });
        return result;
    }

    /** Kuerzt die Wurzelliste bzw. die Listen eines Objekts, damit die Antwort gueltiges JSON bleibt. */
    Result shorten(JsonNode data) {
        if (data.toString().length() <= MAX_RESULT_CHARS) {
            return new Result(data, data.size(), data.size());
        }
        JsonNode copy = data.deepCopy();
        int total = 0;
        int shown = 0;
        while (copy.toString().length() > MAX_RESULT_CHARS) {
            ArrayNode longest = longestArray(copy);
            if (longest == null || longest.isEmpty()) {
                throw new IllegalArgumentException("Antwort zu gross; bitte enger abfragen.");
            }
            int before = longest.size();
            int keep = before / 2;
            while (longest.size() > keep) {
                longest.remove(longest.size() - 1);
            }
            total = Math.max(total, before);
            shown = keep;
        }
        return new Result(copy, total, shown);
    }

    private static ArrayNode longestArray(JsonNode node) {
        ArrayNode best = node.isArray() ? (ArrayNode) node : null;
        int bestLength = best == null ? -1 : best.toString().length();
        for (JsonNode child : node) {
            ArrayNode candidate = node.isArray() && node.size() > 1 ? null : longestArray(child);
            if (candidate != null && candidate.toString().length() > bestLength) {
                best = candidate;
                bestLength = candidate.toString().length();
            }
        }
        return best;
    }
}
