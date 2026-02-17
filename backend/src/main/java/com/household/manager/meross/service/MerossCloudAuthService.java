package com.household.manager.meross.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.household.manager.meross.config.MerossProperties;
import com.household.manager.meross.dto.MerossCloudDevice;
import com.household.manager.meross.dto.MerossCloudDevicesResponse;
import com.household.manager.meross.dto.MerossCloudLoginRequest;
import com.household.manager.meross.dto.MerossCloudLoginResponse;
import com.household.manager.meross.exception.MerossAuthException;
import com.household.manager.meross.exception.MerossException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class MerossCloudAuthService {

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(12);
    private static final String LOGIN_API_PATH = "/v1/Auth/signIn";
    private static final String DEVICE_LIST_API_PATH = "/v1/Device/devList";
    private static final String DEVICE_CONTROL_API_PATH = "/v1/Device/control";

    private static final String AUTHORIZATION_BASIC = "Basic d2VpYmluZzppb3R4XzAwMDAwMDAw";
    private static final String SIGN_SECRET = "23x17ahWarFH6w29";

    private static final String HEADER_VENDOR = "meross";
    private static final String HEADER_APP_VERSION = "0.4.6.2";
    private static final String HEADER_APP_TYPE = "MerossIOT";
    private static final String HEADER_APP_LANGUAGE = "EN";
    private static final String HEADER_USER_AGENT = "okhttp/3.6.0";

    private static final Map<String, String> REGION_TO_HOST = Map.of(
            "eu", "https://iotx-eu.meross.com",
            "us", "https://iotx-us.meross.com",
            "ap", "https://iotx-ap.meross.com"
    );

    private static final Duration SESSION_TTL = Duration.ofHours(23);

    private final ObjectMapper objectMapper;
    private final MerossProperties merossProperties;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(REQUEST_TIMEOUT)
            .build();

    private volatile CloudSession cachedSession;
    private volatile Instant sessionExpiresAt;

    public MerossCloudLoginResponse login(MerossCloudLoginRequest request) {
        return loginInternal(request).response();
    }

    public MerossCloudLoginResponse loginWithConfiguredCredentials() {
        return loginInternal(configuredRequest()).response();
    }

    public MerossCloudDevicesResponse listDevicesWithConfiguredCredentials() {
        return listDevices(configuredRequest());
    }

    public MerossCloudDevicesResponse listDevices(MerossCloudLoginRequest request) {
        String requestId = UUID.randomUUID().toString().substring(0, 8);
        log.info("Meross listDevices start [{}] (region={}, countryCode={})",
                requestId, request.region(), request.countryCode());
        CloudSession session = loginInternal(request);
        try {
            String nonce = randomNonce();
            long timestamp = Instant.now().toEpochMilli();
            String paramsBase64 = Base64.getEncoder().encodeToString("{}".getBytes(StandardCharsets.UTF_8));
            String sign = md5Hex(SIGN_SECRET + timestamp + nonce + paramsBase64);

            ObjectNode requestBody = objectMapper.createObjectNode()
                    .put("timestamp", timestamp)
                    .put("nonce", nonce)
                    .put("params", paramsBase64)
                    .put("sign", sign);

            JsonNode responseNode = sendRequest(
                    session.host() + DEVICE_LIST_API_PATH,
                    requestBody,
                    "Basic " + session.token(),
                    requestId,
                    "devList"
            );

            int apiStatus = responseNode.path("apiStatus").asInt(-1);
            int sysStatus = responseNode.path("sysStatus").asInt(-1);
            String message = readMessage(responseNode);

            if (apiStatus != 0) {
                log.warn("Meross listDevices failed [{}] (apiStatus={}, sysStatus={}, message={})",
                        requestId, apiStatus, sysStatus, message);
                throw new MerossAuthException("Meross device list failed (apiStatus=" + apiStatus
                        + ", sysStatus=" + sysStatus + "): " + message);
            }

            List<MerossCloudDevice> devices = new ArrayList<>();
            JsonNode dataNode = responseNode.path("data");
            JsonNode devListNode = dataNode.isArray() ? dataNode : dataNode.path("devList");
            if (devListNode.isArray()) {
                for (JsonNode node : devListNode) {
                    devices.add(new MerossCloudDevice(
                            node.path("uuid").asText(""),
                            node.path("devName").asText(""),
                            node.path("deviceType").asText(""),
                            node.path("onlineStatus").asText(""),
                            node.path("domain").asText(""),
                            node.path("reservedDomain").asText("")
                    ));
                }
            }

            log.info("Meross listDevices success [{}] (devices={})", requestId, devices.size());
            return new MerossCloudDevicesResponse(apiStatus, sysStatus, message, devices);
        } catch (MerossAuthException ex) {
            log.warn("Meross listDevices auth error [{}]: {}", requestId, ex.getMessage());
            invalidateSession();
            throw ex;
        } catch (MerossException ex) {
            log.warn("Meross listDevices error [{}]: {}", requestId, ex.getMessage());
            throw ex;
        } catch (Exception ex) {
            log.warn("Meross device list failed [{}]", requestId, ex);
            throw new MerossException("Meross device list failed: " + ex.getMessage(), ex);
        }
    }

    public void controlDevice(String deviceUuid, boolean turnOn) {
        String requestId = UUID.randomUUID().toString().substring(0, 8);
        log.info("Meross controlDevice start [{}] (uuid={}, turnOn={})", requestId, deviceUuid, turnOn);

        CloudSession session = loginInternal(configuredRequest());
        try {
            String nonce = randomNonce();
            long timestamp = Instant.now().toEpochMilli();

            // Build control payload
            ObjectNode toggleNode = objectMapper.createObjectNode()
                    .put("channel", 0)
                    .put("onoff", turnOn ? 1 : 0);

            ObjectNode payloadNode = objectMapper.createObjectNode();
            payloadNode.put("uuid", deviceUuid);
            payloadNode.putObject("header")
                    .put("messageId", UUID.randomUUID().toString())
                    .put("method", "SET")
                    .put("namespace", "Appliance.Control.ToggleX")
                    .put("from", "/appliance/" + deviceUuid + "/publish")
                    .put("timestamp", timestamp / 1000)
                    .put("sign", "");
            payloadNode.putObject("payload")
                    .putArray("togglex")
                    .add(toggleNode);

            String payloadJson = objectMapper.writeValueAsString(payloadNode);
            String paramsBase64 = Base64.getEncoder().encodeToString(payloadJson.getBytes(StandardCharsets.UTF_8));
            String sign = md5Hex(SIGN_SECRET + timestamp + nonce + paramsBase64);

            ObjectNode requestBody = objectMapper.createObjectNode()
                    .put("timestamp", timestamp)
                    .put("nonce", nonce)
                    .put("params", paramsBase64)
                    .put("sign", sign);

            JsonNode responseNode = sendRequest(
                    session.host() + DEVICE_CONTROL_API_PATH,
                    requestBody,
                    "Basic " + session.token(),
                    requestId,
                    "control"
            );

            int apiStatus = responseNode.path("apiStatus").asInt(-1);
            int sysStatus = responseNode.path("sysStatus").asInt(-1);
            String message = readMessage(responseNode);

            if (apiStatus != 0) {
                log.warn("Meross controlDevice failed [{}] (apiStatus={}, sysStatus={}, message={})",
                        requestId, apiStatus, sysStatus, message);
                if (apiStatus == 1301) {
                    invalidateSession();
                }
                throw new MerossException("Meross device control failed (apiStatus=" + apiStatus
                        + ", sysStatus=" + sysStatus + "): " + message);
            }

            log.info("Meross controlDevice success [{}]", requestId);
        } catch (MerossAuthException ex) {
            log.warn("Meross controlDevice auth error [{}]: {}", requestId, ex.getMessage());
            invalidateSession();
            throw ex;
        } catch (MerossException ex) {
            log.warn("Meross controlDevice error [{}]: {}", requestId, ex.getMessage());
            throw ex;
        } catch (Exception ex) {
            log.warn("Meross device control failed [{}]", requestId, ex);
            throw new MerossException("Meross device control failed: " + ex.getMessage(), ex);
        }
    }

    public void invalidateSession() {
        cachedSession = null;
        sessionExpiresAt = null;
        log.debug("Meross session cache invalidated");
    }

    private CloudSession loginInternal(MerossCloudLoginRequest request) {
        if (cachedSession != null && sessionExpiresAt != null && Instant.now().isBefore(sessionExpiresAt)) {
            log.debug("Meross login cache hit (expires={})", sessionExpiresAt);
            return cachedSession;
        }

        String requestId = UUID.randomUUID().toString().substring(0, 8);
        log.info("Meross login start [{}] (region={}, countryCode={}, email={})",
                requestId, request.region(), request.countryCode(), request.email());
        try {
            String region = normalizeRegion(request.region());
            String endpoint = REGION_TO_HOST.get(region) + LOGIN_API_PATH;
            String nonce = randomNonce();
            long timestamp = Instant.now().getEpochSecond();
            String passwordHash = normalizePassword(request.password());

            ObjectNode paramsNode = objectMapper.createObjectNode()
                    .put("email", request.email())
                    .put("password", passwordHash)
                    .put("accountCountryCode", request.countryCode())
                    .put("encryption", 1)
                    .put("agree", 0)
                    .put("mfaCode", "");

            String paramsJson = objectMapper.writeValueAsString(paramsNode);
            String paramsBase64 = Base64.getEncoder().encodeToString(paramsJson.getBytes(StandardCharsets.UTF_8));
            String sign = md5Hex(SIGN_SECRET + timestamp + nonce + paramsBase64);

            ObjectNode requestBody = objectMapper.createObjectNode()
                    .put("timestamp", timestamp)
                    .put("nonce", nonce)
                    .put("params", paramsBase64)
                    .put("sign", sign);

            JsonNode responseNode = sendRequest(endpoint, requestBody, AUTHORIZATION_BASIC, requestId, "signIn");
            int apiStatus = responseNode.path("apiStatus").asInt(-1);
            int sysStatus = responseNode.path("sysStatus").asInt(-1);
            String message = readMessage(responseNode);

            JsonNode data = responseNode.path("data");
            String token = data.path("token").asText("");
            String userId = readUserId(data);
            String key = data.path("key").asText("");
            String domain = data.path("domain").asText("");
            String mqttDomain = data.path("mqttDomain").asText("");
            Integer mfaLockExpire = readMfaLockExpire(data);

            if (apiStatus != 0 || token.isEmpty()) {
                log.warn("Meross login failed [{}] (apiStatus={}, sysStatus={}, message={})",
                        requestId, apiStatus, sysStatus, message);
                String errorMessage = "Meross login failed (apiStatus=" + apiStatus + ", sysStatus=" + sysStatus + ")";
                if (!message.isEmpty()) {
                    errorMessage = errorMessage + ": " + message;
                }
                throw new MerossAuthException(errorMessage);
            }

            MerossCloudLoginResponse response = new MerossCloudLoginResponse(
                    apiStatus,
                    sysStatus,
                    message,
                    token,
                    userId,
                    key,
                    domain,
                    mqttDomain,
                    mfaLockExpire
            );
            log.info("Meross login success [{}] (userId={}, domain={})", requestId, userId, domain);
            CloudSession session = new CloudSession(REGION_TO_HOST.get(region), response, key, token);
            cachedSession = session;
            sessionExpiresAt = Instant.now().plus(SESSION_TTL);
            log.debug("Meross session cached (expiresAt={})", sessionExpiresAt);
            return session;
        } catch (MerossException ex) {
            log.warn("Meross login error [{}]: {}", requestId, ex.getMessage());
            throw ex;
        } catch (Exception ex) {
            log.warn("Meross cloud login failed [{}]", requestId, ex);
            throw new MerossException("Meross cloud login failed: " + ex.getMessage(), ex);
        }
    }

    private JsonNode sendRequest(String url, ObjectNode body, String authorizationHeader, String requestId, String operation) throws Exception {
        log.info("Meross HTTP request [{}] op={} url={}", requestId, operation, url);
        log.debug("Meross HTTP payload [{}] op={} body={}", requestId, operation, objectMapper.writeValueAsString(body));
        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(REQUEST_TIMEOUT)
                .header("Authorization", authorizationHeader)
                .header("vender", HEADER_VENDOR)
                .header("appVersion", HEADER_APP_VERSION)
                .header("appType", HEADER_APP_TYPE)
                .header("appLanguage", HEADER_APP_LANGUAGE)
                .header("User-Agent", HEADER_USER_AGENT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                .build();

        HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
        log.info("Meross HTTP response [{}] op={} status={}", requestId, operation, response.statusCode());
        log.debug("Meross HTTP response body [{}] op={} body={}", requestId, operation, response.body());
        if (response.statusCode() == 401) {
            throw new MerossAuthException("Meross HTTP 401 Unauthorized");
        }
        if (response.statusCode() >= 400) {
            throw new MerossException("Meross HTTP error: " + response.statusCode());
        }
        return objectMapper.readTree(response.body());
    }

    private MerossCloudLoginRequest configuredRequest() {
        if (merossProperties.getEmail() == null || merossProperties.getEmail().isBlank()) {
            throw new IllegalStateException("Meross is not configured: 'meross.email' is missing");
        }
        if (merossProperties.getPassword() == null || merossProperties.getPassword().isBlank()) {
            throw new IllegalStateException("Meross is not configured: 'meross.password' is missing");
        }
        String countryCode = merossProperties.getCountryCode() == null || merossProperties.getCountryCode().isBlank()
                ? "de"
                : merossProperties.getCountryCode();
        String region = merossProperties.getRegion() == null || merossProperties.getRegion().isBlank()
                ? "eu"
                : merossProperties.getRegion();

        return new MerossCloudLoginRequest(
                merossProperties.getEmail(),
                merossProperties.getPassword(),
                countryCode,
                region
        );
    }

    private static String readUserId(JsonNode data) {
        String userId = data.path("userid").asText("");
        if (userId.isEmpty()) {
            userId = data.path("userId").asText("");
        }
        return userId;
    }

    private static Integer readMfaLockExpire(JsonNode data) {
        if (!data.has("mfaLockExpire")) {
            return null;
        }
        int value = data.path("mfaLockExpire").asInt(0);
        return value == 0 ? null : value;
    }

    private static String readMessage(JsonNode responseNode) {
        String message = responseNode.path("info").asText("");
        if (message.isEmpty()) {
            message = responseNode.path("message").asText("");
        }
        return message;
    }

    private static String normalizeRegion(String region) {
        if (region == null || region.isBlank()) {
            return "eu";
        }
        String value = region.trim().toLowerCase(Locale.ROOT);
        if (!REGION_TO_HOST.containsKey(value)) {
            throw new IllegalArgumentException("Unsupported Meross region: " + region + " (allowed: eu, us, ap)");
        }
        return value;
    }

    private static String randomNonce() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    private static String normalizePassword(String password) {
        if (password != null && password.matches("(?i)^[a-f0-9]{32}$")) {
            return password.toLowerCase(Locale.ROOT);
        }
        return md5Hex(password);
    }

    private static String md5Hex(String value) {
        try {
            MessageDigest messageDigest = MessageDigest.getInstance("MD5");
            byte[] digest = messageDigest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception ex) {
            throw new MerossException("Could not compute MD5", ex);
        }
    }

    private record CloudSession(String host, MerossCloudLoginResponse response, String key, String token) {
    }
}
