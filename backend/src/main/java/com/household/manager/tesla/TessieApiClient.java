package com.household.manager.tesla;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Dünner HTTP-Client für die Tessie-API ({@code https://api.tessie.com}, Bearer-Token).
 * Alle Tessie-Spezifika (Pfade, Antwortformen) stehen hier und im {@link TessieStateParser}.
 *
 * <p>Ein abgelehnter Token wird zu einer {@link TeslaException} (502 im Controller),
 * nie zu 401 — sonst würfe der Auth-Interceptor des Frontends aus der Haushalts-Session.
 */
@Component
@Slf4j
public class TessieApiClient {

    private final TeslaProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    @Autowired
    public TessieApiClient(TeslaProperties properties, ObjectMapper objectMapper) {
        this(properties, objectMapper, HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10))
                .build());
    }

    TessieApiClient(TeslaProperties properties, ObjectMapper objectMapper, HttpClient httpClient) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
    }

    /** Fahrzeuge des Kontos, je mit dem zuletzt bekannten Zustand. */
    public List<TessieVehicle> listVehicles(String token) {
        JsonNode body = get(token, "/vehicles");
        List<TessieVehicle> vehicles = new ArrayList<>();
        for (JsonNode item : body.path("results")) {
            String vin = item.path("vin").asText(null);
            if (vin == null || vin.isBlank()) {
                continue;
            }
            String name = item.path("last_state").path("display_name").asText(null);
            vehicles.add(new TessieVehicle(vin, name == null || name.isBlank() ? null : name));
        }
        return vehicles;
    }

    /**
     * Zustand eines Fahrzeugs aus dem Tessie-Cache. Weckt das Auto nicht auf; Tessie
     * hält den Stand selbst aktuell.
     */
    public TeslaVehicleSnapshot getState(String token, String vin) {
        return TessieStateParser.parse(get(token, "/" + encode(vin) + "/state"), vin);
    }

    /** Führt einen Befehl aus und wartet, bis das Auto ihn bestätigt (weckt es bei Bedarf). */
    public void sendCommand(String token, String vin, TeslaCommand command) {
        URI uri = URI.create(properties.getBaseUrl() + "/" + encode(vin) + "/command/" + command.getPath()
                + "?wait_for_completion=true");
        HttpRequest request = authorized(token, uri, properties.getCommandTimeoutMs())
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        JsonNode body = send(request);
        if (!body.path("result").asBoolean(false)) {
            String reason = body.path("reason").asText(body.path("error").asText(""));
            throw new TeslaException("Das Auto hat „" + command.getLabel() + "“ nicht ausgeführt"
                    + (reason.isBlank() ? "." : ": " + reason));
        }
    }

    private JsonNode get(String token, String path) {
        HttpRequest request = authorized(token, URI.create(properties.getBaseUrl() + path),
                properties.getReadTimeoutMs()).GET().build();
        return send(request);
    }

    private HttpRequest.Builder authorized(String token, URI uri, int timeoutMs) {
        return HttpRequest.newBuilder(uri)
                .timeout(Duration.ofMillis(timeoutMs))
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/json");
    }

    private JsonNode send(HttpRequest request) {
        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException ex) {
            throw new TeslaException("Tessie nicht erreichbar: " + ex.getMessage(), ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new TeslaException("Tessie-Abruf unterbrochen", ex);
        }
        int status = response.statusCode();
        if (status == 401 || status == 403) {
            throw new TeslaException("Tessie lehnt den Token ab – bitte unter Admin → Tesla prüfen.");
        }
        if (status == 404) {
            throw new TeslaException("Fahrzeug bei Tessie nicht gefunden.");
        }
        if (status == 429) {
            throw new TeslaException("Tessie drosselt die Abrufe (zu viele Anfragen).");
        }
        if (status < 200 || status >= 300) {
            throw new TeslaException("Tessie antwortet mit HTTP " + status + ".");
        }
        try {
            return objectMapper.readTree(response.body());
        } catch (IOException ex) {
            throw new TeslaException("Tessie-Antwort nicht lesbar", ex);
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
