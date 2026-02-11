package com.household.manager.tapo.protocol;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.household.manager.tapo.exception.TapoAuthException;
import com.household.manager.tapo.exception.TapoConnectionException;
import com.household.manager.tapo.exception.TapoException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class TapoProtocolClient {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() { };
    private static final int AES_KEY_LENGTH = 16;
    private static final int AES_IV_LENGTH = 16;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final TapoCipher tapoCipher;

    public TapoSession handshake(String deviceIp) {
        try {
            KeyPair keyPair = tapoCipher.generateKeyPair();
            String pemPublicKey = tapoCipher.publicKeyToPem(keyPair.getPublic());

            Map<String, Object> request = Map.of(
                    "method", "handshake",
                    "params", Map.of("key", pemPublicKey)
            );

            ResponseEntity<Map> responseEntity = post("http://" + deviceIp + "/app", request, null);
            Map<String, Object> body = convertToMap(responseEntity.getBody());
            ensureSuccess(body, "Handshake failed for " + deviceIp);

            Map<String, Object> result = getMap(body, "result");
            String encryptedKey = getString(result, "key");
            byte[] keyIv = tapoCipher.decryptWithPrivateKey(keyPair.getPrivate(), encryptedKey);
            if (keyIv.length < AES_KEY_LENGTH + AES_IV_LENGTH) {
                throw new TapoConnectionException("Handshake response for " + deviceIp + " does not contain AES key/IV");
            }

            byte[] aesKey = Arrays.copyOfRange(keyIv, 0, AES_KEY_LENGTH);
            byte[] iv = Arrays.copyOfRange(keyIv, AES_KEY_LENGTH, AES_KEY_LENGTH + AES_IV_LENGTH);
            String cookie = extractCookie(responseEntity.getHeaders());
            if (cookie == null || cookie.isBlank()) {
                throw new TapoConnectionException("Handshake for " + deviceIp + " did not return session cookie");
            }

            log.debug("Tapo handshake successful for {}", deviceIp);
            return new TapoSession(deviceIp, cookie, keyPair.getPrivate(), aesKey, iv);
        } catch (TapoException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new TapoConnectionException("Failed to perform handshake with device " + deviceIp, ex);
        }
    }

    public String login(TapoSession session, String email, String password) {
        try {
            String encodedEmail = encodeEmail(email);
            String encodedPassword = Base64.getEncoder()
                    .encodeToString(password.getBytes(StandardCharsets.UTF_8));

            Map<String, Object> loginPayload = Map.of(
                    "method", "login_device",
                    "params", Map.of(
                            "username", encodedEmail,
                            "password", encodedPassword
                    )
            );

            Map<String, Object> response = sendSecureRequest(session, null, loginPayload);
            Map<String, Object> result = getMap(response, "result");
            String token = getString(result, "token");
            if (token == null || token.isBlank()) {
                throw new TapoAuthException("Tapo login did not return a token");
            }

            log.debug("Tapo login successful for {}", session.getDeviceIp());
            return token;
        } catch (TapoException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new TapoAuthException("Failed to login to device " + session.getDeviceIp(), ex);
        }
    }

    public Map<String, Object> sendRequest(TapoSession session, String token, Map<String, Object> payload) {
        return sendSecureRequest(session, token, payload);
    }

    private Map<String, Object> sendSecureRequest(TapoSession session, String token, Map<String, Object> payload) {
        try {
            String plainJson = objectMapper.writeValueAsString(payload);
            String encryptedRequest = tapoCipher.encrypt(session.getAesKey(), session.getIv(), plainJson);

            Map<String, Object> wrapper = Map.of(
                    "method", "securePassthrough",
                    "params", Map.of("request", encryptedRequest)
            );

            String url = "http://" + session.getDeviceIp() + "/app" + (token == null ? "" : "?token=" + token);
            ResponseEntity<Map> responseEntity = post(url, wrapper, session.getCookie());
            Map<String, Object> outer = convertToMap(responseEntity.getBody());
            ensureSuccess(outer, "securePassthrough request failed for " + session.getDeviceIp());

            Map<String, Object> result = getMap(outer, "result");
            String encryptedResponse = getString(result, "response");
            String decryptedJson = tapoCipher.decrypt(session.getAesKey(), session.getIv(), encryptedResponse);
            Map<String, Object> decrypted = objectMapper.readValue(decryptedJson, MAP_TYPE);
            ensureSuccess(decrypted, "Inner Tapo request failed for " + session.getDeviceIp());
            return decrypted;
        } catch (TapoException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new TapoConnectionException("Failed to send secure request to device " + session.getDeviceIp(), ex);
        }
    }

    private ResponseEntity<Map> post(String url, Map<String, Object> payload, String cookie) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setAccept(List.of(MediaType.APPLICATION_JSON));
            if (cookie != null && !cookie.isBlank()) {
                headers.set(HttpHeaders.COOKIE, cookie);
            }

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(payload, headers);
            return restTemplate.exchange(url, HttpMethod.POST, entity, Map.class);
        } catch (RestClientException ex) {
            throw new TapoConnectionException("HTTP request to Tapo device failed: " + url, ex);
        }
    }

    private void ensureSuccess(Map<String, Object> response, String message) {
        Integer errorCode = getInteger(response, "error_code");
        if (errorCode != null && errorCode != 0) {
            if (errorCode == -1501 || errorCode == -1502 || errorCode == -20651) {
                throw new TapoAuthException(message + " (error_code=" + errorCode + ")");
            }
            throw new TapoConnectionException(message + " (error_code=" + errorCode + ")");
        }
    }

    private String encodeEmail(String email) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] hash = digest.digest(email.getBytes(StandardCharsets.UTF_8));
            String hex = HexFormat.of().formatHex(hash);
            return Base64.getEncoder().encodeToString(hex.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException ex) {
            throw new TapoException("SHA-1 is not available on this JVM", ex);
        }
    }

    private String extractCookie(HttpHeaders headers) {
        List<String> cookieHeaders = headers.get(HttpHeaders.SET_COOKIE);
        if (cookieHeaders == null || cookieHeaders.isEmpty()) {
            return null;
        }
        String first = cookieHeaders.getFirst();
        int index = first.indexOf(';');
        return index > 0 ? first.substring(0, index) : first;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> convertToMap(Object value) {
        if (value == null) {
            return new LinkedHashMap<>();
        }
        if (value instanceof Map<?, ?> rawMap) {
            return objectMapper.convertValue(rawMap, MAP_TYPE);
        }
        return objectMapper.convertValue(value, MAP_TYPE);
    }

    private Map<String, Object> getMap(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (!(value instanceof Map<?, ?>)) {
            throw new TapoConnectionException("Expected object for key '" + key + "' in Tapo response");
        }
        return convertToMap(value);
    }

    private String getString(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof String text) {
            return text;
        }
        throw new TapoConnectionException("Expected string for key '" + key + "' in Tapo response");
    }

    private Integer getInteger(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text) {
            try {
                return Integer.parseInt(text);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }
}
