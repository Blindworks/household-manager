package com.household.manager.tapo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import javax.crypto.Cipher;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;

public class TapoAesDeviceConnection implements TapoLocalDeviceConnection {

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);
    private static final int SESSION_TTL_SECONDS = 300;

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String username;
    private final String password;
    private final String appUrl;

    private byte[] aesKey;
    private byte[] aesIv;
    private String sessionCookie;
    private String token;
    private Instant authenticatedAt;

    public TapoAesDeviceConnection(
            HttpClient httpClient,
            ObjectMapper objectMapper,
            String username,
            String password,
            String ipAddress
    ) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.username = username;
        this.password = password;
        this.appUrl = "http://" + ipAddress + "/app";
    }

    @Override
    public JsonNode getDeviceInfo() {
        return executeAuthenticatedRequest(objectMapper.createObjectNode().put("method", "get_device_info"));
    }

    @Override
    public void setDevicePowered(boolean poweredOn) {
        ObjectNode request = objectMapper.createObjectNode().put("method", "set_device_info");
        request.set("params", objectMapper.createObjectNode().put("device_on", poweredOn));
        executeAuthenticatedRequest(request);
    }

    @Override
    public JsonNode getEnergyUsage() {
        return executeAuthenticatedRequest(objectMapper.createObjectNode().put("method", "get_energy_usage"));
    }

    @Override
    public JsonNode getCurrentPower() {
        return executeAuthenticatedRequest(objectMapper.createObjectNode().put("method", "get_current_power"));
    }

    private JsonNode executeAuthenticatedRequest(JsonNode requestData) {
        return executeAuthenticatedRequestInternal(requestData, true);
    }

    private JsonNode executeAuthenticatedRequestInternal(JsonNode requestData, boolean retryOnAuthError) {
        ensureAuthenticated();

        ObjectNode passthrough = objectMapper.createObjectNode().put("method", "securePassthrough");
        passthrough.set("params", objectMapper.createObjectNode().put("request", encrypt(requestData.toString())));

        JsonNode response = postJson(appUrl + "?token=" + token, passthrough.toString(), sessionCookie);

        int errorCode = response.path("error_code").asInt(0);
        if ((errorCode == -1301 || errorCode == 9999) && retryOnAuthError) {
            invalidateSession();
            return executeAuthenticatedRequestInternal(requestData, false);
        }
        validateResponse(response, "Tapo AES securePassthrough");

        String encryptedResponse = response.path("result").path("response").asText(null);
        if (encryptedResponse == null || encryptedResponse.isBlank()) {
            throw new TapoException("Tapo AES-Antwort enthaelt keine Nutzdaten.");
        }

        try {
            JsonNode parsed = objectMapper.readTree(decrypt(encryptedResponse));

            int innerError = parsed.path("error_code").asInt(0);
            if (innerError == -1301 && retryOnAuthError) {
                invalidateSession();
                return executeAuthenticatedRequestInternal(requestData, false);
            }

            validateResponse(parsed, "Tapo AES-Geraet");
            return parsed.path("result");
        } catch (IOException ex) {
            throw new TapoException("Tapo AES-Antwort konnte nicht gelesen werden.", ex);
        }
    }

    private synchronized void invalidateSession() {
        token = null;
        authenticatedAt = null;
        sessionCookie = null;
        aesKey = null;
        aesIv = null;
    }

    private synchronized void ensureAuthenticated() {
        if (token != null && authenticatedAt != null && Instant.now().isBefore(authenticatedAt.plusSeconds(SESSION_TTL_SECONDS))) {
            return;
        }

        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(1024);
            KeyPair keyPair = generator.generateKeyPair();

            ObjectNode handshake = objectMapper.createObjectNode().put("method", "handshake");
            handshake.set("params", objectMapper.createObjectNode().put("key", publicKeyPem(keyPair)));

            HttpResponse<String> handshakeResponse = postJsonRaw(appUrl, handshake.toString(), null);
            if (handshakeResponse.statusCode() == 403) {
                throw new TapoException("Tapo AES verweigert den Zugriff (403). Pruefe 'Third-Party Compatibility' in der Tapo-App.");
            }

            sessionCookie = TapoSessionCookie.extract(handshakeResponse.headers());
            JsonNode handshakeJson = objectMapper.readTree(handshakeResponse.body());
            validateResponse(handshakeJson, "Tapo AES-Handshake");

            String handshakeKey = handshakeJson.path("result").path("key").asText(null);
            if (handshakeKey == null || handshakeKey.isBlank()) {
                throw new TapoException("Tapo AES-Handshake lieferte keinen Schluessel.");
            }

            byte[] decryptedKey = decryptRsa(Base64.getDecoder().decode(handshakeKey), keyPair.getPrivate());
            if (decryptedKey.length != 32) {
                throw new TapoException("Tapo AES-Handshake lieferte ungueltiges Schluesselmaterial (Laenge: " + decryptedKey.length + ").");
            }

            aesKey = Arrays.copyOfRange(decryptedKey, 0, 16);
            aesIv = Arrays.copyOfRange(decryptedKey, 16, 32);

            String usernameDigest = Base64.getEncoder()
                    .encodeToString(TapoCipher.sha1DigestUsername(username).getBytes(StandardCharsets.UTF_8));
            String passwordEncoded = Base64.getEncoder().encodeToString(password.getBytes(StandardCharsets.UTF_8));

            ObjectNode login = objectMapper.createObjectNode().put("method", "login_device");
            login.set("params", objectMapper.createObjectNode()
                    .put("username", usernameDigest)
                    .put("password", passwordEncoded)
                    .put("requestTimeMils", System.currentTimeMillis()));

            JsonNode loginResult = executeSecurePassthroughWithoutToken(login);
            token = loginResult.path("token").asText(null);
            if (token == null || token.isBlank()) {
                throw new TapoException("Tapo AES-Login lieferte kein Token.");
            }
            authenticatedAt = Instant.now();
        } catch (GeneralSecurityException | IOException ex) {
            throw new TapoException("Tapo AES-Authentifizierung fehlgeschlagen: " + ex.getMessage(), ex);
        }
    }

    private JsonNode executeSecurePassthroughWithoutToken(JsonNode requestData) throws IOException {
        ObjectNode passthrough = objectMapper.createObjectNode().put("method", "securePassthrough");
        passthrough.set("params", objectMapper.createObjectNode().put("request", encrypt(requestData.toString())));

        JsonNode response = postJson(appUrl, passthrough.toString(), sessionCookie);
        validateResponse(response, "Tapo AES-Login");

        JsonNode parsed = objectMapper.readTree(decrypt(response.path("result").path("response").asText("")));
        validateResponse(parsed, "Tapo AES-Login");
        return parsed.path("result");
    }

    private String encrypt(String plainText) {
        return Base64.getEncoder().encodeToString(
                TapoCipher.aesCbcEncrypt(aesKey, aesIv, plainText.getBytes(StandardCharsets.UTF_8))
        );
    }

    private String decrypt(String cipherTextBase64) {
        byte[] decrypted = TapoCipher.aesCbcDecrypt(aesKey, aesIv, Base64.getDecoder().decode(cipherTextBase64));
        return new String(decrypted, StandardCharsets.UTF_8);
    }

    private HttpResponse<String> postJsonRaw(String url, String payload, String cookie) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .timeout(REQUEST_TIMEOUT)
                    .POST(HttpRequest.BodyPublishers.ofString(payload));
            if (cookie != null) {
                builder.header("Cookie", cookie);
            }
            return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException | InterruptedException ex) {
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new TapoException("Tapo AES-HTTP-Aufruf fehlgeschlagen: " + ex.getMessage(), ex);
        }
    }

    private JsonNode postJson(String url, String payload, String cookie) {
        try {
            return objectMapper.readTree(postJsonRaw(url, payload, cookie).body());
        } catch (IOException ex) {
            throw new TapoException("Tapo AES-Antwort konnte nicht gelesen werden.", ex);
        }
    }

    private static String publicKeyPem(KeyPair keyPair) {
        String base64 = Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(keyPair.getPublic().getEncoded());
        return "-----BEGIN PUBLIC KEY-----\n" + base64 + "\n-----END PUBLIC KEY-----\n";
    }

    private static byte[] decryptRsa(byte[] encrypted, PrivateKey privateKey) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        cipher.init(Cipher.DECRYPT_MODE, privateKey);
        return cipher.doFinal(encrypted);
    }

    private static void validateResponse(JsonNode response, String source) {
        int errorCode = response.path("error_code").asInt(0);
        if (errorCode == 0) {
            return;
        }
        throw new TapoException(source + "-Fehler " + errorCode + ": "
                + response.path("msg").asText(response.path("result").path("msg").asText("Unbekannter Fehler")));
    }
}
