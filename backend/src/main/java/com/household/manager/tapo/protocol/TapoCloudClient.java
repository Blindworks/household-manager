package com.household.manager.tapo.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.household.manager.tapo.exception.TapoAuthException;
import com.household.manager.tapo.exception.TapoCommunicationException;
import com.household.manager.tapo.exception.TapoException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Client for communicating with Tapo Cloud API.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TapoCloudClient {

    private static final String CLOUD_API_URL = "https://wap.tplinkcloud.com";
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(REQUEST_TIMEOUT)
            .build();

    /**
     * Authenticates with Tapo Cloud and returns a session token.
     */
    public String login(String email, String password) {
        try {
            Map<String, Object> requestBody = Map.of(
                    "method", "login",
                    "params", Map.of(
                            "appType", "Tapo_Android",
                            "cloudUserName", email,
                            "cloudPassword", password,
                            "terminalUUID", UUID.randomUUID().toString()
                    )
            );

            String responseBody = sendHttpRequest(CLOUD_API_URL, requestBody, null);
            JsonNode response = objectMapper.readTree(responseBody);

            int errorCode = response.path("error_code").asInt(-1);
            if (errorCode != 0) {
                String message = response.path("msg").asText("Unknown error");
                throw new TapoAuthException("Login failed: " + message + " (code: " + errorCode + ")");
            }

            String token = response.path("result").path("token").asText();
            if (token == null || token.isEmpty()) {
                throw new TapoAuthException("Login successful but no token received");
            }

            log.info("Successfully logged in to Tapo Cloud");
            return token;
        } catch (TapoException ex) {
            throw ex;
        } catch (Exception ex) {
            log.error("Failed to login to Tapo Cloud", ex);
            throw new TapoAuthException("Login failed: " + ex.getMessage(), ex);
        }
    }

    /**
     * Retrieves list of devices from Tapo Cloud.
     */
    public JsonNode getDeviceList(String token) {
        try {
            Map<String, Object> requestBody = Map.of("method", "getDeviceList");
            String responseBody = sendHttpRequest(CLOUD_API_URL + "?token=" + token, requestBody, token);
            JsonNode response = objectMapper.readTree(responseBody);

            int errorCode = response.path("error_code").asInt(-1);
            if (errorCode != 0) {
                String message = response.path("msg").asText("Unknown error");
                throw new TapoCommunicationException("Failed to get device list: " + message + " (code: " + errorCode + ")");
            }

            return response.path("result").path("deviceList");
        } catch (TapoException ex) {
            throw ex;
        } catch (Exception ex) {
            log.error("Failed to get device list", ex);
            throw new TapoCommunicationException("Failed to get device list: " + ex.getMessage(), ex);
        }
    }

    /**
     * Sends a passthrough request to a specific device.
     */
    public Map<String, Object> sendDeviceRequest(String token, String deviceId, Map<String, Object> request) {
        try {
            // Encode the request as JSON string for passthrough
            String requestData = objectMapper.writeValueAsString(request);

            Map<String, Object> requestBody = Map.of(
                    "method", "passthrough",
                    "params", Map.of(
                            "deviceId", deviceId,
                            "requestData", requestData
                    )
            );

            String responseBody = sendHttpRequest(CLOUD_API_URL + "?token=" + token, requestBody, token);
            JsonNode response = objectMapper.readTree(responseBody);

            int errorCode = response.path("error_code").asInt(-1);
            if (errorCode != 0) {
                String message = response.path("msg").asText("Unknown error");
                throw new TapoCommunicationException("Device request failed: " + message + " (code: " + errorCode + ")");
            }

            // Parse the response data
            String responseData = response.path("result").path("responseData").asText();
            if (responseData != null && !responseData.isEmpty()) {
                JsonNode innerResponse = objectMapper.readTree(responseData);
                return objectMapper.convertValue(innerResponse, Map.class);
            }

            return new HashMap<>();
        } catch (TapoException ex) {
            throw ex;
        } catch (Exception ex) {
            log.error("Failed to send device request", ex);
            throw new TapoCommunicationException("Failed to send device request: " + ex.getMessage(), ex);
        }
    }

    private String sendHttpRequest(String url, Map<String, Object> body, String token) {
        try {
            String jsonBody = objectMapper.writeValueAsString(body);

            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody));

            if (token != null) {
                requestBuilder.header("Authorization", "Bearer " + token);
            }

            HttpRequest request = requestBuilder.build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() >= 400) {
                throw new TapoCommunicationException("HTTP error: " + response.statusCode());
            }

            return response.body();
        } catch (TapoCommunicationException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new TapoCommunicationException("HTTP request failed: " + ex.getMessage(), ex);
        }
    }
}
