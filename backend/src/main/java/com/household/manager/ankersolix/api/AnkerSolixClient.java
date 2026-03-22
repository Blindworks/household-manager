package com.household.manager.ankersolix.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Map;
import java.util.logging.Logger;

public class AnkerSolixClient {

    private static final Logger log = Logger.getLogger(AnkerSolixClient.class.getName());

    private static final String LOGIN_ENDPOINT = "passport/login";

    private static final Map<String, String> BASE_URLS = Map.of(
            "EU",  "https://ankerpower-api-eu.anker.com/",
            "US",  "https://ankerpower-api.anker.com/",
            "COM", "https://ankerpower-api.anker.com/"
    );

    private static final String DEFAULT_REGION = "COM";
    private static final String APP_NAME   = "anker_power";
    private static final String MODEL_TYPE = "DESKTOP";
    private static final String OS_TYPE    = "android";

    private final String email;
    private final String password;
    private final String country;
    private final String region;
    private final String baseUrl;

    private final HttpClient   httpClient;
    private final ObjectMapper objectMapper;
    private final AnkerCrypto  crypto;
    private final TokenCache   tokenCache;

    public AnkerSolixClient(String email, String password, String country) {
        this.email    = email;
        this.password = password;
        this.country  = country.toUpperCase();
        this.region   = resolveRegion(this.country);
        this.baseUrl  = BASE_URLS.get(this.region);

        this.httpClient   = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();
        this.objectMapper = new ObjectMapper();
        this.crypto       = new AnkerCrypto();
        this.tokenCache   = new TokenCache();

        log.info("AnkerSolixClient initialised – region=" + region + " baseUrl=" + baseUrl);
    }

    public void authenticate() throws Exception {
        if (!tokenCache.isValid()) {
            log.info("Token invalid or absent – logging in …");
            login();
        } else {
            log.fine("Reusing cached token.");
        }
    }

    public JsonNode request(String endpoint, ObjectNode payload) throws Exception {
        authenticate();

        String url  = baseUrl + endpoint;
        String body = objectMapper.writeValueAsString(payload);

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("content-type", "application/json")
                .header("model-type",   MODEL_TYPE)
                .header("app-name",     APP_NAME)
                .header("os-type",      OS_TYPE)
                .header("country",      country)
                .header("timezone",     buildTimezone())
                .header("x-auth-token", tokenCache.getAuthToken())
                .header("gtoken",       tokenCache.getGtoken())
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        return parseResponse(resp, endpoint);
    }

    public JsonNode request(String endpoint) throws Exception {
        return request(endpoint, objectMapper.createObjectNode());
    }

    private void login() throws Exception {
        String encryptedPassword = crypto.encryptPassword(password);

        ObjectNode clientSecretInfo = objectMapper.createObjectNode();
        clientSecretInfo.put("public_key", crypto.getClientPublicKeyHex());

        ObjectNode loginPayload = objectMapper.createObjectNode();
        loginPayload.put("ab",                 country);
        loginPayload.set("client_secret_info", clientSecretInfo);
        loginPayload.put("enc",                0);
        loginPayload.put("email",              email);
        loginPayload.put("password",           encryptedPassword);
        loginPayload.put("time_zone",          buildTimezoneMillis());
        loginPayload.put("transaction",        AnkerCrypto.generateTransaction());

        String url  = baseUrl + LOGIN_ENDPOINT;
        String body = objectMapper.writeValueAsString(loginPayload);

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("content-type", "application/json")
                .header("model-type",   MODEL_TYPE)
                .header("app-name",     APP_NAME)
                .header("os-type",      OS_TYPE)
                .header("country",      country)
                .header("timezone",     buildTimezone())
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        log.fine("Login response [" + resp.statusCode() + "]: " + resp.body());
        JsonNode json = parseResponse(resp, LOGIN_ENDPOINT);

        JsonNode data = json.get("data");
        if (data == null || data.isNull()) {
            throw new AnkerApiException("Login response contained no data field.");
        }

        String authToken    = data.path("auth_token").asText();
        String encryptedUid = data.path("user_id").asText();
        String gtoken       = AnkerCrypto.md5(encryptedUid);
        long   expiresAt    = data.has("token_expires_at")
                ? data.get("token_expires_at").asLong()
                : System.currentTimeMillis() / 1000 + 7200;

        tokenCache.store(authToken, gtoken, expiresAt);
        log.info("Login successful – token cached.");
    }

    private JsonNode parseResponse(HttpResponse<String> resp, String endpoint) throws Exception {
        if (resp.statusCode() != 200) {
            throw new AnkerApiException(
                    "HTTP " + resp.statusCode() + " from " + endpoint + ": " + resp.body());
        }
        JsonNode json = objectMapper.readTree(resp.body());
        int code = json.path("code").asInt(0);
        if (code != 0) {
            String msg = json.path("msg").asText("unknown error");
            throw new AnkerApiException("API error " + code + " – " + msg);
        }
        return json;
    }

    private static String buildTimezone() {
        ZoneOffset offset = ZonedDateTime.now().getOffset();
        int totalSeconds = offset.getTotalSeconds();
        int hours   = totalSeconds / 3600;
        int minutes = Math.abs((totalSeconds % 3600) / 60);
        return String.format("GMT%+03d:%02d", hours, minutes);
    }

    /** UTC offset in milliseconds, as expected by the login endpoint. */
    private static int buildTimezoneMillis() {
        return ZonedDateTime.now().getOffset().getTotalSeconds() * 1000;
    }

    private static String resolveRegion(String country) {
        String[] euCountries = {
                "AT","BE","BG","HR","CY","CZ","DK","EE","FI","FR",
                "DE","GR","HU","IE","IT","LV","LT","LU","MT","NL",
                "PL","PT","RO","SK","SI","ES","SE","GB","CH","NO","IS","LI"
        };
        for (String c : euCountries) {
            if (c.equals(country)) return "EU";
        }
        if ("US".equals(country) || "CA".equals(country)) return "US";
        return DEFAULT_REGION;
    }
}