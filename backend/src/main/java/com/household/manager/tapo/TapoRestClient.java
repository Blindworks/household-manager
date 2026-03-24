package com.household.manager.tapo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * HTTP client for the tapo-rest sidecar (ClementNerma/tapo-rest).
 * Provides local LAN control of Tapo devices via the Rust-based REST API.
 */
@Component
@Slf4j
public class TapoRestClient {

    private final TapoProperties tapoProperties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    private volatile String sessionToken;

    public TapoRestClient(TapoProperties tapoProperties, ObjectMapper objectMapper) {
        this.tapoProperties = tapoProperties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    public boolean isAvailable() {
        return tapoProperties.isRestEnabled();
    }

    public List<TapoRestDevice> getDevices() {
        ensureAuthenticated();
        JsonNode response = sendGet("/devices");
        List<TapoRestDevice> devices = new ArrayList<>();
        if (response != null && response.isArray()) {
            for (JsonNode node : response) {
                devices.add(new TapoRestDevice(
                        node.path("name").asText(),
                        node.path("device_type").asText()
                ));
            }
        }
        return devices;
    }

    public void turnOn(String deviceName) {
        ensureAuthenticated();
        sendPost("/actions/" + encode(deviceName) + "/on", null);
        log.info("Tapo device '{}' eingeschaltet via tapo-rest (lokal)", deviceName);
    }

    public void turnOff(String deviceName) {
        ensureAuthenticated();
        sendPost("/actions/" + encode(deviceName) + "/off", null);
        log.info("Tapo device '{}' ausgeschaltet via tapo-rest (lokal)", deviceName);
    }

    public JsonNode getDeviceInfo(String deviceName) {
        ensureAuthenticated();
        return sendGet("/actions/" + encode(deviceName) + "/get-device-info");
    }

    public JsonNode getEnergyUsage(String deviceName) {
        ensureAuthenticated();
        return sendGet("/actions/" + encode(deviceName) + "/get-energy-usage");
    }

    public JsonNode getCurrentPower(String deviceName) {
        ensureAuthenticated();
        return sendGet("/actions/" + encode(deviceName) + "/get-current-power");
    }

    private void ensureAuthenticated() {
        if (sessionToken != null) {
            return;
        }
        login();
    }

    private void login() {
        try {
            String body = objectMapper.writeValueAsString(Map.of("password", tapoProperties.getRestPassword()));
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(tapoProperties.getRestUrl() + "/login"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                JsonNode json = objectMapper.readTree(response.body());
                sessionToken = json.path("session_id").asText(null);
                if (sessionToken == null) {
                    sessionToken = json.asText(response.body().replace("\"", ""));
                }
                log.info("Erfolgreich bei tapo-rest angemeldet");
            } else {
                throw new TapoException("tapo-rest Login fehlgeschlagen: HTTP " + response.statusCode() + " - " + response.body());
            }
        } catch (TapoException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new TapoException("tapo-rest Login fehlgeschlagen: " + ex.getMessage(), ex);
        }
    }

    private JsonNode sendGet(String path) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(tapoProperties.getRestUrl() + path))
                    .header("Authorization", "Bearer " + sessionToken)
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return handleResponse(response, path);
        } catch (TapoException ex) {
            throw ex;
        } catch (IOException | InterruptedException ex) {
            throw new TapoException("tapo-rest GET " + path + " fehlgeschlagen: " + ex.getMessage(), ex);
        }
    }

    private JsonNode sendPost(String path, Object body) {
        try {
            String jsonBody = body != null ? objectMapper.writeValueAsString(body) : "";
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(tapoProperties.getRestUrl() + path))
                    .header("Authorization", "Bearer " + sessionToken)
                    .header("Content-Type", "application/json");
            if (body != null) {
                builder.POST(HttpRequest.BodyPublishers.ofString(jsonBody));
            } else {
                builder.POST(HttpRequest.BodyPublishers.noBody());
            }
            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            return handleResponse(response, path);
        } catch (TapoException ex) {
            throw ex;
        } catch (IOException | InterruptedException ex) {
            throw new TapoException("tapo-rest POST " + path + " fehlgeschlagen: " + ex.getMessage(), ex);
        }
    }

    private JsonNode handleResponse(HttpResponse<String> response, String path) {
        if (response.statusCode() == 401) {
            // Session expired, re-login and retry once
            sessionToken = null;
            login();
            return null;
        }
        if (response.statusCode() >= 200 && response.statusCode() < 300) {
            try {
                String body = response.body();
                if (body == null || body.isBlank() || body.equals("null")) {
                    return null;
                }
                return objectMapper.readTree(body);
            } catch (Exception ex) {
                return null;
            }
        }
        throw new TapoException("tapo-rest " + path + ": HTTP " + response.statusCode() + " - " + response.body());
    }

    private String encode(String deviceName) {
        return deviceName.replace(" ", "%20");
    }

    public record TapoRestDevice(String name, String deviceType) {}
}
