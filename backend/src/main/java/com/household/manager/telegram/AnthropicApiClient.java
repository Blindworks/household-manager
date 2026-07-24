package com.household.manager.telegram;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** HTTP-Client für die Anthropic Messages API (Tool-Use). */
@Component
public class AnthropicApiClient {

    private static final String API_VERSION = "2023-06-01";
    /** Antworten der Claude API brauchen deutlich länger als lokale Aufrufe. */
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(60);

    private final TelegramProperties properties;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AnthropicApiClient(TelegramProperties properties, RestTemplateBuilder builder) {
        this.properties = properties;
        this.restTemplate = builder
                .connectTimeout(Duration.ofMillis(properties.getHttpTimeoutMs()))
                .readTimeout(READ_TIMEOUT)
                .build();
    }

    public AnthropicResponse createMessage(String system, List<AnthropicMessage> messages,
                                           List<Map<String, Object>> tools) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", properties.getModel());
        body.put("max_tokens", properties.getMaxTokens());
        body.put("system", system);
        body.put("messages", messages);
        if (!tools.isEmpty()) {
            body.put("tools", tools);
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("x-api-key", properties.getAnthropicApiKey());
        headers.set("anthropic-version", API_VERSION);

        try {
            JsonNode root = restTemplate.postForObject(
                    properties.getAnthropicBaseUrl() + "/v1/messages",
                    new HttpEntity<>(body, headers), JsonNode.class);
            if (root == null) {
                throw new TelegramException("Claude API lieferte keine Antwort", null);
            }
            List<Map<String, Object>> content = objectMapper.convertValue(
                    root.path("content"), new TypeReference<>() {
                    });
            return new AnthropicResponse(root.path("stop_reason").asText(), content);
        } catch (RestClientException ex) {
            throw new TelegramException("Claude API-Aufruf fehlgeschlagen: " + ex.getMessage(), ex);
        }
    }

    /** Nur für Tests (MockRestServiceServer). */
    RestTemplate restTemplate() {
        return restTemplate;
    }
}
