package com.household.manager.alexa;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

/**
 * HTTP-Client fuer den alexa-remote2-Sidecar. Der Sidecar uebernimmt den Amazon-Login
 * (Browser-Proxy) sowie die Alexa-Kommunikation (Geraeteliste, TTS); dieses Backend ruft
 * ihn nur ueber eine schlanke HTTP-API auf.
 */
@Service
@Slf4j
public class AlexaSidecarClient {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private final AlexaProperties properties;
    private final ObjectMapper mapper;
    private final HttpClient httpClient;

    public AlexaSidecarClient(AlexaProperties properties, ObjectMapper mapper) {
        this.properties = properties;
        this.mapper = mapper;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    /** Ein vom Sidecar gemeldetes Echo-Geraet. */
    public record SidecarDevice(String serialNumber, String name, String deviceType, boolean ttsCapable) {}

    /** Login-Status laut Sidecar. */
    public record SidecarStatus(boolean loggedIn, String accountName) {}

    public SidecarStatus getStatus() {
        JsonNode root = get("/status");
        return new SidecarStatus(
                root.path("loggedIn").asBoolean(false),
                root.path("accountName").asText(null));
    }

    /** Startet den Browser-Login und liefert die URL, die der Nutzer im Browser oeffnet. */
    public String startLogin() {
        JsonNode root = post("/login/start", null);
        String url = root.path("proxyUrl").asText(null);
        if (url == null || url.isBlank()) {
            throw new AlexaException("Sidecar lieferte keine Login-URL.");
        }
        return url;
    }

    public void logout() {
        post("/logout", null);
    }

    public List<SidecarDevice> getDevices() {
        JsonNode root = get("/devices");
        try {
            return mapper.convertValue(root, new TypeReference<>() {});
        } catch (IllegalArgumentException ex) {
            throw new AlexaException("Sidecar-Geraeteliste konnte nicht gelesen werden.", ex);
        }
    }

    public void speak(String serialNumber, String text) {
        ObjectNode body = mapper.createObjectNode();
        body.put("serialNumber", serialNumber);
        body.put("text", text);
        post("/speak", body);
    }

    public void announce(List<String> serialNumbers, String text) {
        ObjectNode body = mapper.createObjectNode();
        ArrayNode serials = body.putArray("serialNumbers");
        serialNumbers.forEach(serials::add);
        body.put("text", text);
        post("/announce", body);
    }

    // ==================== HTTP ====================

    private JsonNode get(String path) {
        return send("GET", path, null);
    }

    private JsonNode post(String path, JsonNode body) {
        return send("POST", path, body);
    }

    private JsonNode send(String method, String path, JsonNode body) {
        String url = properties.getSidecar().getBaseUrl() + path;
        try {
            HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(TIMEOUT)
                    .header("Accept", "application/json");
            if ("GET".equals(method)) {
                req.GET();
            } else {
                req.header("Content-Type", "application/json")
                        .method(method, HttpRequest.BodyPublishers.ofString(
                                body == null ? "{}" : mapper.writeValueAsString(body)));
            }
            HttpResponse<String> response = httpClient.send(req.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new AlexaException("Alexa-Sidecar " + path + " HTTP " + response.statusCode()
                        + ": " + extractError(response.body()));
            }
            if (response.body() == null || response.body().isBlank()) {
                return mapper.createObjectNode();
            }
            return mapper.readTree(response.body());
        } catch (AlexaException ex) {
            throw ex;
        } catch (java.net.ConnectException ex) {
            throw new AlexaException("Alexa-Sidecar ist nicht erreichbar (" + url + "). Laeuft der Dienst?", ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AlexaException("Alexa-Sidecar-Kommunikation unterbrochen.", ex);
        } catch (Exception ex) {
            throw new AlexaException("Alexa-Sidecar-Kommunikation fehlgeschlagen: " + path, ex);
        }
    }

    private String extractError(String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        try {
            return mapper.readTree(body).path("error").asText(body);
        } catch (Exception ex) {
            return body;
        }
    }
}
