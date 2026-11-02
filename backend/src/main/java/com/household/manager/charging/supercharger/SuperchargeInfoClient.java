package com.household.manager.charging.supercharger;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.household.manager.charging.ChargingProperties;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Laedt die Standortliste von supercharge.info (inoffiziell, community-gepflegt). Alle
 * Spezifika der Quelle (URL, Feldnamen) leben ausschliesslich hier. Fehler gehen als
 * Exception an den Aufrufer.
 */
@Component
public class SuperchargeInfoClient {

    private final ChargingProperties properties;
    private final ObjectMapper mapper;
    private final HttpClient httpClient;

    public SuperchargeInfoClient(ChargingProperties properties, ObjectMapper mapper) {
        this.properties = properties;
        this.mapper = mapper;
        this.httpClient = createHttpClient(properties.getSupercharger().getTimeoutMs());
    }

    /** HTTP/1.1 erzwungen (Projektmuster); eigene Methode fuer den Regressionstest. */
    static HttpClient createHttpClient(int timeoutMs) {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(timeoutMs))
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    public List<SuperchargerSite> fetchSites() throws IOException, InterruptedException {
        ChargingProperties.Supercharger config = properties.getSupercharger();
        HttpRequest request = HttpRequest.newBuilder(URI.create(config.getUrl()))
                .timeout(Duration.ofMillis(config.getTimeoutMs()))
                .header("User-Agent", "household-manager")
                .header("Accept", "application/json")
                .GET().build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("supercharge.info antwortet mit HTTP " + response.statusCode());
        }
        return parseSites(mapper, response.body());
    }

    static List<SuperchargerSite> parseSites(ObjectMapper mapper, String json) {
        try {
            JsonNode root = mapper.readTree(json);
            if (root == null || !root.isArray()) {
                throw new IllegalStateException("supercharge.info: Array erwartet");
            }
            List<SuperchargerSite> sites = new ArrayList<>();
            for (JsonNode node : root) {
                sites.add(toSite(node));
            }
            return sites;
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("supercharge.info: Antwort nicht lesbar: " + ex.getOriginalMessage(), ex);
        }
    }

    private static SuperchargerSite toSite(JsonNode node) {
        JsonNode address = node.path("address");
        JsonNode gps = node.path("gps");
        JsonNode power = node.path("powerKilowatt");
        return new SuperchargerSite(
                node.path("id").asText(),
                text(node, "name"),
                text(address, "street"),
                text(address, "city"),
                gps.path("latitude").isNumber() ? gps.path("latitude").asDouble() : null,
                gps.path("longitude").isNumber() ? gps.path("longitude").asDouble() : null,
                node.path("stallCount").asInt(0),
                power.isNumber() ? power.asInt() : null,
                node.path("otherEVs").asBoolean(false),
                text(node, "status"));
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isTextual() ? value.asText() : null;
    }
}
