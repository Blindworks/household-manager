package com.household.manager.ai;

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

/** HTTP-Client fuer die Anthropic Messages API (Tool-Use), geteilt von Telegram-Bot und Hausverstand. */
@Component
public class AnthropicApiClient {

    private static final String API_VERSION = "2023-06-01";
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(60);

    private final AiProperties properties;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AnthropicApiClient(AiProperties properties, RestTemplateBuilder builder) {
        this.properties = properties;
        this.restTemplate = builder
                .connectTimeout(Duration.ofMillis(properties.getHttpTimeoutMs()))
                .readTimeout(READ_TIMEOUT)
                .build();
    }

    public AnthropicResponse createMessage(AnthropicRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", request.model());
        body.put("max_tokens", request.maxTokens());
        body.put("system", request.system());
        body.put("messages", request.messages());
        if (request.tools() != null && !request.tools().isEmpty()) {
            body.put("tools", request.tools());
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
                throw new AiException("Claude API lieferte keine Antwort", null);
            }
            List<Map<String, Object>> content = objectMapper.convertValue(
                    root.path("content"), new TypeReference<>() {
                    });
            JsonNode usage = root.path("usage");
            return new AnthropicResponse(root.path("stop_reason").asText(), content,
                    usage.path("input_tokens").asInt(0), usage.path("output_tokens").asInt(0));
        } catch (RestClientException ex) {
            throw new AiException("Claude API-Aufruf fehlgeschlagen: " + ex.getMessage(), ex);
        }
    }

    /** Nur fuer Tests (MockRestServiceServer). */
    RestTemplate restTemplate() {
        return restTemplate;
    }
}
