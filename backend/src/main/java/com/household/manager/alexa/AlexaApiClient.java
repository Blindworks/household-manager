package com.household.manager.alexa;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** HTTP-Aufrufe gegen alexa.<domain> mit einer gueltigen Sitzung. */
@Service
@Slf4j
public class AlexaApiClient {

    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    private final AlexaProperties properties;
    private final ObjectMapper mapper;
    private final HttpClient httpClient;

    public AlexaApiClient(AlexaProperties properties, ObjectMapper mapper) {
        this.properties = properties;
        this.mapper = mapper;
        this.httpClient = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
    }

    private String baseUrl() {
        return "https://alexa." + properties.getDomain();
    }

    /** GET /api/devices-v2/device — alle Echos des Kontos. */
    public List<AlexaRemoteDevice> listDevices(AlexaSession session) {
        JsonNode root = getJson(session, "/api/devices-v2/device?cached=false");
        List<AlexaRemoteDevice> result = new ArrayList<>();
        for (JsonNode d : root.path("devices")) {
            String serial = d.path("serialNumber").asText(null);
            if (serial == null || serial.isBlank()) {
                continue;
            }
            List<String> caps = new ArrayList<>();
            d.path("capabilities").forEach(c -> caps.add(c.asText()));
            result.add(new AlexaRemoteDevice(
                    serial,
                    d.path("accountName").asText(serial),
                    d.path("deviceType").asText(null),
                    d.path("deviceFamily").asText(null),
                    caps));
        }
        return result;
    }

    /** POST /api/behaviors/preview — spielt die zuvor gebaute Sequenz ab. */
    public void sendBehavior(AlexaSession session, String behaviorBody) {
        HttpResponse<String> response = send(session, "POST", "/api/behaviors/preview", behaviorBody);
        if (response.statusCode() / 100 != 2) {
            throw new AlexaException("Alexa behaviors/preview HTTP " + response.statusCode()
                    + ": " + response.body());
        }
    }

    private JsonNode getJson(AlexaSession session, String path) {
        HttpResponse<String> response = send(session, "GET", path, null);
        if (response.statusCode() / 100 != 2) {
            throw new AlexaException("Alexa GET " + path + " HTTP " + response.statusCode());
        }
        try {
            return mapper.readTree(response.body());
        } catch (Exception ex) {
            throw new AlexaException("Alexa-Antwort konnte nicht gelesen werden: " + path, ex);
        }
    }

    private HttpResponse<String> send(AlexaSession session, String method, String path, String body) {
        try {
            HttpRequest.Builder req = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl() + path))
                    .timeout(TIMEOUT)
                    .header("Cookie", session.getCookie())
                    .header("csrf", session.getCsrf())
                    .header("Content-Type", "application/json; charset=UTF-8")
                    .header("Accept", "application/json")
                    .header("User-Agent", "Mozilla/5.0 (Household-Manager)")
                    .header("Referer", baseUrl() + "/spa/index.html")
                    .header("Origin", baseUrl());
            if ("GET".equals(method)) {
                req.GET();
            } else {
                req.method(method, HttpRequest.BodyPublishers.ofString(body == null ? "" : body));
            }
            return httpClient.send(req.build(), HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AlexaException("Alexa-Kommunikation unterbrochen.", ex);
        } catch (Exception ex) {
            throw new AlexaException("Alexa-Kommunikation fehlgeschlagen: " + path, ex);
        }
    }
}
