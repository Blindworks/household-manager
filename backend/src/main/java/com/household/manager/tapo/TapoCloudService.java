package com.household.manager.tapo;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * TP-Link Tapo Cloud API V2 client.
 * <p>
 * Uses HMAC-SHA1 signed requests as required by the current Tapo cloud infrastructure.
 * Based on the verified implementation from piekstra/tplink-cloud-api (Python).
 * <p>
 * The old V1 API (wap.tplinkcloud.com with simple token auth) can list Tapo devices
 * but cannot control them (returns -20571). The V2 API is what the Tapo mobile app uses.
 */
@Service
@Slf4j
public class TapoCloudService {

    // Tapo app-level signing keys (from APK, identify the app not the user)
    private static final String TAPO_ACCESS_KEY = "4d11b6b9d5ea4d19a829adbb9714b057";
    private static final String TAPO_SECRET_KEY = "6ed7d97f3e73467f8a5bab90b577ba4c";
    private static final String TAPO_HOST = "https://n-wap.i.tplinkcloud.com";
    private static final String TAPO_APP_TYPE = "TP-Link_Tapo_Android";
    private static final String TAPO_APP_VER = "3.4.451";
    private static final String SIGNING_TIMESTAMP = "9999999999";
    private static final String USER_AGENT = "Dalvik/2.1.0 (Linux; U; Android 14; Pixel Build/UP1A)";

    private static final String PATH_ACCOUNT_STATUS = "/api/v2/account/getAccountStatusAndUrl";
    private static final String PATH_LOGIN = "/api/v2/account/login";
    private static final String PATH_PASSTHROUGH = "/api/v2/common/passthrough";

    private static final long DEVICE_LIST_CACHE_TTL_MS = 60_000;
    private static final java.time.Duration REQUEST_TIMEOUT = java.time.Duration.ofSeconds(15);

    private final ObjectMapper objectMapper;
    private final TapoProperties tapoProperties;
    private final HttpClient httpClient;

    private final String terminalUuid = UUID.randomUUID().toString();
    private final Map<String, String> appServerUrlCache = new ConcurrentHashMap<>();

    private volatile String cloudToken;
    private volatile Instant tokenExpiresAt;
    private volatile String regionalUrl;
    private volatile List<TapoCloudDevice> cachedDeviceList;
    private volatile Instant deviceListCachedAt;

    public TapoCloudService(ObjectMapper objectMapper, TapoProperties tapoProperties) {
        this.objectMapper = objectMapper;
        this.tapoProperties = tapoProperties;
        this.httpClient = createTrustAllHttpClient();
    }

    /**
     * TP-Link cloud servers use certificates that may not be trusted by default
     * Java trust stores (some have expired certs). All reference implementations
     * (Python, TypeScript) disable SSL verification for this reason.
     */
    private static HttpClient createTrustAllHttpClient() {
        try {
            TrustManager[] trustAllCerts = new TrustManager[]{
                    new X509TrustManager() {
                        @Override
                        public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                        @Override
                        public void checkClientTrusted(X509Certificate[] certs, String authType) {}
                        @Override
                        public void checkServerTrusted(X509Certificate[] certs, String authType) {}
                    }
            };
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, trustAllCerts, new java.security.SecureRandom());
            return HttpClient.newBuilder()
                    .sslContext(sslContext)
                    .connectTimeout(REQUEST_TIMEOUT)
                    .build();
        } catch (Exception ex) {
            log.warn("Konnte keinen SSL-toleranten HttpClient erstellen, verwende Standard: {}", ex.getMessage());
            return HttpClient.newHttpClient();
        }
    }

    public List<TapoCloudDevice> getTapoDevices() {
        return getTapoDevices(false);
    }

    public List<TapoCloudDevice> getTapoDevices(boolean forceRefresh) {
        if (!forceRefresh && cachedDeviceList != null && deviceListCachedAt != null
                && Instant.now().isBefore(deviceListCachedAt.plusMillis(DEVICE_LIST_CACHE_TTL_MS))) {
            return cachedDeviceList;
        }

        ensureAuthenticated();

        // V1-style body with V2 signing (this is how the Tapo app does it)
        ObjectNode body = objectMapper.createObjectNode().put("method", "getDeviceList");
        JsonNode response = postSigned(regionalUrl, "/", body);

        JsonNode deviceListNode = response.path("result").path("deviceList");
        List<TapoCloudDevice> devices = objectMapper.convertValue(deviceListNode, new TypeReference<>() {});

        devices.forEach(d -> {
            if (d.deviceId() != null && d.appServerUrl() != null && !d.appServerUrl().isBlank()) {
                appServerUrlCache.put(d.deviceId(), d.appServerUrl());
            }
            log.debug("Cloud-Geraet: id={}, alias={}, model={}, type={}, status={}, appServer={}",
                    d.deviceId(), d.alias(), d.model(), d.deviceType(), d.status(), d.appServerUrl());
        });

        List<TapoCloudDevice> tapoDevices = devices.stream()
                .filter(this::isTapoDevice)
                .toList();
        cachedDeviceList = tapoDevices;
        deviceListCachedAt = Instant.now();
        log.info("Tapo-Geraetliste aktualisiert: {} Tapo-Geraete (gesamt: {})", tapoDevices.size(), devices.size());
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

    /**
     * Send a passthrough command to a Tapo device via V2 Cloud API.
     */
    public JsonNode passthrough(String deviceId, JsonNode requestData) {
        ensureAuthenticated();
        String appServerUrl = resolveAppServerUrl(deviceId);

        // Tapo V2 passthrough uses flat body (NOT wrapped in method/params)
        ObjectNode body = objectMapper.createObjectNode();
        body.put("deviceId", deviceId);
        body.put("requestData", requestData.toString());

        log.debug("V2 passthrough fuer {}: server={}, request={}", deviceId, appServerUrl, requestData);
        JsonNode response = postSigned(appServerUrl, PATH_PASSTHROUGH, body);

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

    // ==================== Authentication ====================

    private synchronized void ensureAuthenticated() {
        if (cloudToken != null && tokenExpiresAt != null && Instant.now().isBefore(tokenExpiresAt)) {
            return;
        }

        validateConfiguration();

        // Step 1: Discover regional URL
        if (regionalUrl == null) {
            regionalUrl = discoverRegionalUrl();
            log.info("Tapo regional URL: {}", regionalUrl);
        }

        // Step 2: Login on regional URL
        ObjectNode loginBody = objectMapper.createObjectNode();
        loginBody.put("appType", TAPO_APP_TYPE);
        loginBody.put("appVersion", TAPO_APP_VER);
        loginBody.put("cloudPassword", tapoProperties.getPassword());
        loginBody.put("cloudUserName", tapoProperties.getEmail());
        loginBody.put("platform", "Android");
        loginBody.put("refreshTokenNeeded", true);
        loginBody.put("supportBindAccount", false);
        loginBody.put("terminalUUID", terminalUuid);
        loginBody.put("terminalName", "Pixel");
        loginBody.put("terminalMeta", "Pixel");

        JsonNode loginResponse = postSigned(regionalUrl, PATH_LOGIN, loginBody);
        String token = loginResponse.path("result").path("token").asText(null);
        if (token == null || token.isBlank()) {
            throw new TapoException("Tapo V2 Login lieferte kein Token.");
        }

        cloudToken = token;
        tokenExpiresAt = Instant.now().plusMillis(tapoProperties.getCloudTokenExpiryMs());
        log.info("Tapo V2 Login erfolgreich; Token bis {} gecacht", tokenExpiresAt);
    }

    private String discoverRegionalUrl() {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("appType", TAPO_APP_TYPE);
        body.put("cloudUserName", tapoProperties.getEmail());

        try {
            JsonNode response = postSigned(TAPO_HOST, PATH_ACCOUNT_STATUS, body);
            String url = response.path("result").path("appServerUrl").asText(null);
            if (url != null && !url.isBlank()) {
                return url;
            }
        } catch (Exception ex) {
            log.warn("Regional URL Erkennung fehlgeschlagen, verwende Standard: {}", ex.getMessage());
        }
        return TAPO_HOST;
    }

    private String resolveAppServerUrl(String deviceId) {
        String url = appServerUrlCache.get(deviceId);
        if (url == null) {
            log.debug("appServerUrl fuer {} nicht im Cache, lade Geraetliste", deviceId);
            getTapoDevices(true);
            url = appServerUrlCache.get(deviceId);
        }
        if (url == null || url.isBlank()) {
            log.warn("Keine appServerUrl fuer {} gefunden, verwende Regional-URL", deviceId);
            return regionalUrl != null ? regionalUrl : TAPO_HOST;
        }
        return url;
    }

    // ==================== V2 HTTP with HMAC-SHA1 Signing ====================

    /**
     * Send a signed POST request to the TP-Link V2 Cloud API.
     */
    private JsonNode postSigned(String baseUrl, String urlPath, JsonNode body) {
        try {
            String bodyJson = objectMapper.writeValueAsString(body);

            // Build signed headers
            String contentMd5 = computeContentMd5(bodyJson);
            String nonce = UUID.randomUUID().toString();
            String signString = contentMd5 + "\n" + SIGNING_TIMESTAMP + "\n" + nonce + "\n" + urlPath;
            String signature = hmacSha1(TAPO_SECRET_KEY, signString);

            String authorization = "Timestamp=" + SIGNING_TIMESTAMP
                    + ", Nonce=" + nonce
                    + ", AccessKey=" + TAPO_ACCESS_KEY
                    + ", Signature=" + signature;

            // Build URL with query parameters
            String url = baseUrl + urlPath + buildQueryString();

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Content-Type", "application/json;charset=UTF-8")
                    .header("User-Agent", USER_AGENT)
                    .header("Content-MD5", contentMd5)
                    .header("X-Authorization", authorization)
                    .POST(HttpRequest.BodyPublishers.ofString(bodyJson))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new TapoException("Tapo V2 API HTTP " + response.statusCode() + ": " + response.body());
            }

            JsonNode root = objectMapper.readTree(response.body());
            log.debug("V2 Antwort: path={}, error_code={}", urlPath, root.path("error_code").asInt(0));
            ensureSuccess(root, "Tapo-Cloud-V2");
            return root;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new TapoException("Tapo V2 Kommunikation unterbrochen.", ex);
        } catch (IOException ex) {
            throw new TapoException("Tapo V2 Kommunikation fehlgeschlagen.", ex);
        }
    }

    private String buildQueryString() {
        StringBuilder sb = new StringBuilder("?");
        sb.append("appName=").append(TAPO_APP_TYPE);
        sb.append("&appVer=").append(TAPO_APP_VER);
        sb.append("&netType=wifi");
        sb.append("&termID=").append(terminalUuid);
        sb.append("&ospf=Android+14");
        sb.append("&brand=TPLINK");
        sb.append("&locale=en_US");
        sb.append("&model=Pixel");
        sb.append("&termName=Pixel");
        sb.append("&termMeta=Pixel");
        if (cloudToken != null) {
            sb.append("&token=").append(cloudToken);
        }
        return sb.toString();
    }

    // ==================== Crypto ====================

    private static String computeContentMd5(String body) {
        try {
            byte[] md5 = MessageDigest.getInstance("MD5").digest(body.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(md5);
        } catch (Exception ex) {
            throw new TapoException("MD5 Berechnung fehlgeschlagen.", ex);
        }
    }

    private static String hmacSha1(String key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
            byte[] hash = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception ex) {
            throw new TapoException("HMAC-SHA1 Berechnung fehlgeschlagen.", ex);
        }
    }

    // ==================== Helpers ====================

    boolean isTapoDevice(TapoCloudDevice device) {
        String type = safeLower(device.deviceType());
        String model = safeLower(device.model());

        // Kasa devices live in the same TP-Link cloud account but are controlled through the
        // local Kasa integration. Their cloud deviceType is "iot.*" (e.g. IOT.SMARTPLUGSWITCH)
        // or contains "kasa". Excluding them here stops duplicate Kasa entries from being
        // persisted under Tapo. This exclusion wins over the permissive model-prefix heuristics
        // below, which would otherwise misclassify Kasa models (e.g. LB/KL bulbs, cameras).
        if (type.startsWith("iot") || type.contains("kasa")) {
            return false;
        }

        return type.contains("smart.tapo")
                || model.startsWith("p1")
                || model.startsWith("l")
                || model.startsWith("c")
                || model.startsWith("s")
                || model.startsWith("t")
                || model.startsWith("ke");
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

    private static String safeLower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }
}
