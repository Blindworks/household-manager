package com.household.manager.telegram;

import com.household.manager.telegram.dto.TelegramUpdate;
import com.household.manager.telegram.dto.TelegramUpdatesResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Dünner HTTP-Client für die Telegram-Bot-API: nur getUpdates (Long-Polling)
 * und sendMessage. Der Read-Timeout liegt bewusst über dem Poll-Timeout,
 * sonst bricht jeder leere Long-Poll mit einem Timeout ab.
 */
@Component
@Slf4j
public class TelegramApiClient {

    /** Harte Obergrenze der Telegram-Bot-API für Nachrichtentexte. */
    static final int MAX_MESSAGE_LENGTH = 4096;

    private final TelegramProperties properties;
    private final RestTemplate restTemplate;

    public TelegramApiClient(TelegramProperties properties, RestTemplateBuilder builder) {
        this.properties = properties;
        this.restTemplate = builder
                .connectTimeout(Duration.ofMillis(properties.getHttpTimeoutMs()))
                .readTimeout(Duration.ofSeconds(properties.getPollTimeoutSeconds() + 10L))
                .build();
    }

    public List<TelegramUpdate> getUpdates(long offset, int timeoutSeconds) {
        String url = apiUrl("getUpdates") + "?offset=" + offset + "&timeout=" + timeoutSeconds;
        try {
            TelegramUpdatesResponse response = restTemplate.getForObject(url, TelegramUpdatesResponse.class);
            return response != null && response.result() != null ? response.result() : List.of();
        } catch (RestClientException ex) {
            throw new TelegramException("Telegram getUpdates fehlgeschlagen: " + masked(ex), ex);
        }
    }

    public void sendMessage(long chatId, String text) {
        String payload = text.length() > MAX_MESSAGE_LENGTH ? text.substring(0, MAX_MESSAGE_LENGTH) : text;
        try {
            restTemplate.postForObject(apiUrl("sendMessage"),
                    Map.of("chat_id", chatId, "text", payload), String.class);
        } catch (RestClientException ex) {
            throw new TelegramException("Telegram sendMessage fehlgeschlagen: " + masked(ex), ex);
        }
    }

    private String apiUrl(String method) {
        return properties.getTelegramBaseUrl() + "/bot" + properties.getBotToken() + "/" + method;
    }

    /** RestClient-Fehlertexte enthalten die URL — und damit den Token. */
    private String masked(RestClientException ex) {
        String message = String.valueOf(ex.getMessage());
        return message.replace(properties.getBotToken(), "***");
    }

    /** Nur für Tests (MockRestServiceServer). */
    RestTemplate restTemplate() {
        return restTemplate;
    }
}
