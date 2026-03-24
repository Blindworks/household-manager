package com.household.manager.tapo;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
@Slf4j
public class TapoCloudService {

    private static final String APP_TYPE = "Tapo_Android";

    private final ObjectMapper objectMapper;
    private final TapoProperties tapoProperties;
    private final HttpClient httpClient = HttpClient.newHttpClient();

    private static final long DEVICE_LIST_CACHE_TTL_MS = 60_000;
    private static final long TOKEN_EXPIRY_BUFFER_MS = 60_000;

    private volatile String terminalUuid = UUID.randomUUID().toString();
    private final Map<String, String> appServerUrlCache = new ConcurrentHashMap<>();
    private final Map<String, TokenEntry> tokenCache = new ConcurrentHashMap<>();
    private volatile List<TapoCloudDevice> cachedDeviceList;
    private volatile Instant deviceListCachedAt;

    public List<TapoCloudDevice> getTapoDevices() {
        return getTapoDevices(false);
    }

    public List<TapoCloudDevice> getTapoDevices(boolean forceRefresh) {
        if (!forceRefresh && cachedDeviceList != null && deviceListCachedAt != null
                && Instant.now().isBefore(deviceListCachedAt.plusMillis(DEVICE_LIST_CACHE_TTL_MS))) {
            return cachedDeviceList;
        }

        JsonNode deviceListNode = invokeCloud("getDeviceList", null, true,
                tapoProperties.getCloudApiUrl()).path("result").path("deviceList");
        List<TapoCloudDevice> devices = objectMapper.convertValue(deviceListNode, new TypeReference<>() {});

        devices.forEach(d -> {
            if (d.deviceId() != null && d.appServerUrl() != null && !d.appServerUrl().isBlank()) {
                appServerUrlCache.put(d.deviceId(), d.appServerUrl());
            }
            log.debug("Cloud-Geraet: id={}, alias={}, model={}, type={}, status={}, appServerUrl={}",
                    d.deviceId(), d.alias(), d.model(), d.deviceType(), d.status(), d.appServerUrl());
        });

        List<TapoCloudDevice> tapoDevices = devices.stream()
                .filter(this::isTapoDevice)
                .toList();
        cachedDeviceList = tapoDevices;
        deviceListCachedAt = Instant.now();
        log.info("Tapo-Geraetliste aktualisiert: {} Geraete (gesamt: {})", tapoDevices.size(), devices.size());
        return tapoDevices;
    }

    public TapoCloudDevice findDeviceById(String deviceId) {
        return getTapoDevices().stream()
                .filter(device -> deviceId.equals(device.deviceId()))
                .findFirst()
                .orElse(null);
    }

    public JsonNode getDeviceInfo(String deviceId) {
        ObjectNode request = objectMapper.createObjectNode();
        request.put("method", "get_device_info");
        return passthrough(deviceId, request);
    }

    public void setDevicePowered(String deviceId, boolean poweredOn) {
        ObjectNode request = objectMapper.createObjectNode();
        request.put("method", "set_device_info");
        request.set("params", objectMapper.createObjectNode().put("device_on", poweredOn));
        passthrough(deviceId, request);
    }

    public JsonNode getEnergyUsage(String deviceId) {
        ObjectNode request = objectMapper.createObjectNode();
        request.put("method", "get_energy_usage");
        return passthrough(deviceId, request);
    }

    public JsonNode passthrough(String deviceId, JsonNode requestData) {
        String appServerUrl = resolveAppServerUrl(deviceId);

        ObjectNode params = objectMapper.createObjectNode();
        params.put("deviceId", deviceId);
        params.put("requestData", requestData.toString());

        log.debug("Passthrough fuer {}: server={}, request={}", deviceId, appServerUrl, requestData);
        JsonNode response = invokeCloud("passthrough", params, true, appServerUrl);
        String nestedResponse = response.path("result").path("responseData").asText(null);
        if (nestedResponse == null || nestedResponse.isBlank()) {
            throw new TapoException("Tapo-Cloud lieferte keine Antwortdaten fuer Geraet " + deviceId);
        }

        try {
            JsonNode parsed = objectMapper.readTree(nestedResponse);
            ensureSuccess(parsed, "Tapo-Geraet");
            return parsed.path("result");
        } catch (IOException ex) {
            throw new TapoException("Tapo-Geraetantwort konnte nicht gelesen werden.", ex);
        }
    }

    public String decodeAlias(String alias) {
        if (alias == null || alias.isBlank()) {
            return alias;
        }
        if (!alias.matches("^[A-Za-z0-9+/]*={0,2}$") || alias.length() % 4 != 0) {
            return alias;
        }

        try {
            byte[] decoded = Base64.getDecoder().decode(alias);
            String value = new String(decoded, StandardCharsets.UTF_8).trim();
            return value.isBlank() ? alias : value;
        } catch (IllegalArgumentException ex) {
            return alias;
        }
    }

    private boolean isTapoDevice(TapoCloudDevice device) {
        String type = safeLower(device.deviceType());
        String model = safeLower(device.model());
        return type.contains("smart.tapo")
                || model.startsWith("p1")
                || model.startsWith("l")
                || model.startsWith("c")
                || model.startsWith("s")
                || model.startsWith("t")
                || model.startsWith("ke");
    }

    private String ensureTokenForServer(String serverUrl) {
        validateConfiguration();

        String normalizedUrl = normalizeServerUrl(serverUrl);
        TokenEntry entry = tokenCache.get(normalizedUrl);
        if (entry != null && Instant.now().isBefore(entry.expiresAt.minusMillis(TOKEN_EXPIRY_BUFFER_MS))) {
            return entry.token;
        }

        synchronized (this) {
            entry = tokenCache.get(normalizedUrl);
            if (entry != null && Instant.now().isBefore(entry.expiresAt.minusMillis(TOKEN_EXPIRY_BUFFER_MS))) {
                return entry.token;
            }

            log.info("Tapo-Cloud-Login fuer Server: {}", normalizedUrl);
            ObjectNode params = objectMapper.createObjectNode();
            params.put("appType", APP_TYPE);
            params.put("cloudUserName", tapoProperties.getEmail());
            params.put("cloudPassword", tapoProperties.getPassword());
            params.put("terminalUUID", terminalUuid);

            JsonNode loginResponse = invokeCloud("login", params, false, normalizedUrl);
            String token = loginResponse.path("result").path("token").asText(null);
            if (token == null || token.isBlank()) {
                throw new TapoException("Tapo-Cloud-Login fuer " + normalizedUrl + " lieferte kein Token.");
            }

            Instant expiresAt = Instant.now().plusMillis(tapoProperties.getCloudTokenExpiryMs());
            tokenCache.put(normalizedUrl, new TokenEntry(token, expiresAt));
            log.info("Tapo-Cloud-Login fuer {} erfolgreich; Token bis {} gecacht", normalizedUrl, expiresAt);
            return token;
        }
    }

    private String resolveAppServerUrl(String deviceId) {
        String url = appServerUrlCache.get(deviceId);
        if (url == null) {
            log.debug("appServerUrl fuer {} nicht im Cache, lade Geraetliste", deviceId);
            getTapoDevices(true);
            url = appServerUrlCache.get(deviceId);
        }
        if (url == null || url.isBlank()) {
            log.warn("Keine appServerUrl fuer {} gefunden, verwende Standard-Cloud-URL", deviceId);
            return tapoProperties.getCloudApiUrl();
        }
        return url;
    }

    private JsonNode invokeCloud(String method, JsonNode params, boolean withToken, String baseUrl) {
        try {
            ObjectNode payload = objectMapper.createObjectNode();
            payload.put("method", method);
            if (params != null) {
                payload.set("params", params);
            }

            String url = baseUrl;
            if (withToken) {
                url = url + "?token=" + ensureTokenForServer(baseUrl);
            }

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode root = objectMapper.readTree(response.body());
            log.debug("Cloud-Antwort von {}: method={}, error_code={}", baseUrl, method, root.path("error_code").asInt(0));
            ensureSuccess(root, "Tapo-Cloud");
            return root;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new TapoException("Tapo-Cloud-Kommunikation wurde unterbrochen.", ex);
        } catch (IOException ex) {
            throw new TapoException("Tapo-Cloud-Kommunikation fehlgeschlagen.", ex);
        }
    }

    private void ensureSuccess(JsonNode response, String source) {
        int errorCode = response.path("error_code").asInt(0);
        if (errorCode == 0) {
            return;
        }

        String message = response.path("msg").asText(null);
        if (message == null || message.isBlank()) {
            message = response.path("result").path("msg").asText("Unbekannter Fehler");
        }
        throw new TapoException(source + "-Fehler " + errorCode + ": " + message);
    }

    private void validateConfiguration() {
        if (tapoProperties.getEmail() == null || tapoProperties.getEmail().isBlank()) {
            throw new IllegalStateException("Tapo ist nicht konfiguriert: 'tapo.email' fehlt.");
        }
        if (tapoProperties.getPassword() == null || tapoProperties.getPassword().isBlank()) {
            throw new IllegalStateException("Tapo ist nicht konfiguriert: 'tapo.password' fehlt.");
        }
    }

    private static String normalizeServerUrl(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static String safeLower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private record TokenEntry(String token, Instant expiresAt) {}
}
