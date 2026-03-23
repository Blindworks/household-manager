package com.household.manager.tapo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

public class TapoKlapDeviceConnection implements TapoLocalDeviceConnection {

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String username;
    private final String password;
    private final String appUrl;

    private String sessionCookie;
    private byte[] key;
    private byte[] ivPrefix;
    private byte[] signatureSeed;
    private AtomicInteger sequence;
    private Instant authenticatedAt;

    public TapoKlapDeviceConnection(
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
        return executeRequest(objectMapper.createObjectNode().put("method", "get_device_info"));
    }

    @Override
    public void setDevicePowered(boolean poweredOn) {
        ObjectNode request = objectMapper.createObjectNode().put("method", "set_device_info");
        request.set("params", objectMapper.createObjectNode().put("device_on", poweredOn));
        executeRequest(request);
    }

    private JsonNode executeRequest(JsonNode requestData) {
        ensureAuthenticated();
        int seq = sequence.incrementAndGet();
        byte[] payload = encrypt(requestData.toString(), seq);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(appUrl + "/request?seq=" + seq))
                .header("Cookie", sessionCookie)
                .POST(HttpRequest.BodyPublishers.ofByteArray(payload))
                .build();

        try {
            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new TapoException("Tapo KLAP-Request fehlgeschlagen mit HTTP " + response.statusCode());
            }

            JsonNode parsed = objectMapper.readTree(decrypt(response.body(), seq));
            validateResponse(parsed, "Tapo KLAP-Geraet");
            return parsed.path("result");
        } catch (IOException | InterruptedException ex) {
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new TapoException("Tapo KLAP-Kommunikation fehlgeschlagen.", ex);
        }
    }

    private synchronized void ensureAuthenticated() {
        if (authenticatedAt != null && Instant.now().isBefore(authenticatedAt.plusSeconds(300))) {
            return;
        }

        byte[] userHash = TapoCipher.sha256(concat(
                TapoCipher.sha1(username.getBytes(StandardCharsets.UTF_8)),
                TapoCipher.sha1(password.getBytes(StandardCharsets.UTF_8))
        ));

        byte[] localSeed = new byte[16];
        new SecureRandom().nextBytes(localSeed);

        try {
            HttpRequest handshake1 = HttpRequest.newBuilder()
                    .uri(URI.create(appUrl + "/handshake1"))
                    .POST(HttpRequest.BodyPublishers.ofByteArray(localSeed))
                    .build();
            HttpResponse<byte[]> response1 = httpClient.send(handshake1, HttpResponse.BodyHandlers.ofByteArray());
            if (response1.statusCode() == 403) {
                throw new TapoException("Tapo KLAP verweigert den Zugriff. Pruefe Third-Party Compatibility in der Tapo-App.");
            }
            if (response1.statusCode() < 200 || response1.statusCode() >= 300) {
                throw new TapoException("Tapo KLAP-Handshake1 fehlgeschlagen mit HTTP " + response1.statusCode());
            }

            sessionCookie = TapoSessionCookie.extract(response1.headers());
            byte[] body = response1.body();
            if (body.length < 48) {
                throw new TapoException("Tapo KLAP-Handshake1 lieferte ungueltige Daten.");
            }

            byte[] remoteSeed = Arrays.copyOfRange(body, 0, 16);
            byte[] serverHash = Arrays.copyOfRange(body, 16, 48);
            byte[] localHash = TapoCipher.sha256(concat(localSeed, remoteSeed, userHash));
            if (!Arrays.equals(localHash, serverHash)) {
                throw new TapoException("Tapo KLAP-Handshake-Pruefung fehlgeschlagen.");
            }

            byte[] payload = TapoCipher.sha256(concat(remoteSeed, localSeed, userHash));
            HttpRequest handshake2 = HttpRequest.newBuilder()
                    .uri(URI.create(appUrl + "/handshake2"))
                    .header("Cookie", sessionCookie)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(payload))
                    .build();
            HttpResponse<byte[]> response2 = httpClient.send(handshake2, HttpResponse.BodyHandlers.ofByteArray());
            if (response2.statusCode() < 200 || response2.statusCode() >= 300) {
                throw new TapoException("Tapo KLAP-Handshake2 fehlgeschlagen mit HTTP " + response2.statusCode());
            }

            byte[] sessionHash = TapoCipher.sha256(concat(localSeed, remoteSeed, userHash));
            key = Arrays.copyOf(TapoCipher.sha256(concat("lsk".getBytes(StandardCharsets.UTF_8), sessionHash)), 16);
            byte[] ivDigest = TapoCipher.sha256(concat("iv".getBytes(StandardCharsets.UTF_8), sessionHash));
            ivPrefix = Arrays.copyOf(ivDigest, 12);
            sequence = new AtomicInteger(ByteBuffer.wrap(ivDigest, ivDigest.length - 4, 4).getInt());
            signatureSeed = Arrays.copyOf(TapoCipher.sha256(concat("ldk".getBytes(StandardCharsets.UTF_8), sessionHash)), 28);
            authenticatedAt = Instant.now();
        } catch (IOException | InterruptedException ex) {
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new TapoException("Tapo KLAP-Authentifizierung fehlgeschlagen.", ex);
        }
    }

    private byte[] encrypt(String requestData, int seq) {
        byte[] cipherText = TapoCipher.aesCbcEncrypt(key, ivForSequence(seq), requestData.getBytes(StandardCharsets.UTF_8));
        byte[] signature = TapoCipher.sha256(concat(signatureSeed, ByteBuffer.allocate(4).putInt(seq).array(), cipherText));
        return concat(signature, cipherText);
    }

    private String decrypt(byte[] payload, int seq) {
        if (payload.length < 33) {
            throw new TapoException("Tapo KLAP-Antwort ist zu kurz.");
        }
        byte[] cipherText = Arrays.copyOfRange(payload, 32, payload.length);
        return new String(TapoCipher.aesCbcDecrypt(key, ivForSequence(seq), cipherText), StandardCharsets.UTF_8);
    }

    private byte[] ivForSequence(int seq) {
        return concat(ivPrefix, ByteBuffer.allocate(4).putInt(seq).array());
    }

    private static byte[] concat(byte[]... arrays) {
        int total = 0;
        for (byte[] array : arrays) {
            total += array.length;
        }
        byte[] result = new byte[total];
        int offset = 0;
        for (byte[] array : arrays) {
            System.arraycopy(array, 0, result, offset, array.length);
            offset += array.length;
        }
        return result;
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
