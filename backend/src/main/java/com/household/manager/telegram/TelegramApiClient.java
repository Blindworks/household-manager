package com.household.manager.telegram;

import com.fasterxml.jackson.databind.JsonNode;
import com.household.manager.telegram.dto.TelegramUpdate;
import com.household.manager.telegram.dto.TelegramUpdatesResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Dünner HTTP-Client für die Telegram-Bot-API: getUpdates (Long-Polling), sendMessage
 * (optional mit Inline-Knöpfen), editMessageReplyMarkup und answerCallbackQuery. Der Read-Timeout liegt bewusst über dem Poll-Timeout,
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
        sendMessage(chatId, text, List.of());
    }

    /**
     * Sendet eine Nachricht, optional mit Inline-Knöpfen, und liefert die {@code message_id} (0, falls
     * die Antwort keine trägt). Die Id braucht der Aufrufer, um die Knöpfe später wieder zu entfernen.
     * Ohne Knöpfe entfällt {@code reply_markup} ganz — ein leeres Keyboard wäre für Telegram kein No-op.
     */
    public long sendMessage(long chatId, String text, List<List<InlineButton>> keyboard) {
        String payload = text.length() > MAX_MESSAGE_LENGTH ? text.substring(0, MAX_MESSAGE_LENGTH) : text;
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("chat_id", chatId);
        body.put("text", payload);
        if (keyboard != null && !keyboard.isEmpty()) {
            body.put("reply_markup", Map.of("inline_keyboard", keyboardRows(keyboard)));
        }
        try {
            JsonNode root = restTemplate.postForObject(apiUrl("sendMessage"), body, JsonNode.class);
            return root == null ? 0L : root.path("result").path("message_id").asLong(0L);
        } catch (RestClientException ex) {
            throw new TelegramException("Telegram sendMessage fehlgeschlagen: " + masked(ex), ex);
        }
    }

    /** Ersetzt die Knöpfe einer gesendeten Nachricht; eine leere Liste entfernt sie (Vorschlag entschieden). */
    public void editReplyMarkup(long chatId, long messageId, List<List<InlineButton>> keyboard) {
        Map<String, Object> body = Map.of("chat_id", chatId, "message_id", messageId,
                "reply_markup", Map.of("inline_keyboard", keyboardRows(keyboard == null ? List.of() : keyboard)));
        try {
            restTemplate.postForObject(apiUrl("editMessageReplyMarkup"), body, String.class);
        } catch (RestClientException ex) {
            throw new TelegramException("Telegram editMessageReplyMarkup fehlgeschlagen: " + masked(ex), ex);
        }
    }

    /** Ohne diese Antwort zeigt der Telegram-Client nach einem Knopfdruck minutenlang einen Ladekreisel. */
    public void answerCallbackQuery(String callbackQueryId, String text) {
        try {
            restTemplate.postForObject(apiUrl("answerCallbackQuery"),
                    Map.of("callback_query_id", callbackQueryId, "text", text), String.class);
        } catch (RestClientException ex) {
            throw new TelegramException("Telegram answerCallbackQuery fehlgeschlagen: " + masked(ex), ex);
        }
    }

    private List<List<Map<String, String>>> keyboardRows(List<List<InlineButton>> keyboard) {
        return keyboard.stream().map(row -> row.stream()
                .map(b -> Map.of("text", b.text(), "callback_data", b.callbackData())).toList()).toList();
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
