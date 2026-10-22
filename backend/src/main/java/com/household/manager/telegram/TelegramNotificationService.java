package com.household.manager.telegram;

import com.household.manager.common.DeliveryResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Fire-and-forget-Versand von Telegram-Nachrichten (Flow-Node, künftige
 * Benachrichtigungen). Wirft nie — ein Sendefehler darf keinen Flow abbrechen
 * (Verhalten analog Alexa-Ansagen).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TelegramNotificationService {

    private static final String NOT_CONFIGURED = "Telegram nicht konfiguriert";

    private final TelegramProperties properties;
    private final TelegramApiClient apiClient;

    /** Sendet an alle erlaubten Chats; Fehler einzelner Chats stoppen die anderen nicht. */
    public DeliveryResult sendToAllowedChats(String text) {
        if (!properties.isConfigured()) {
            log.debug("Telegram nicht konfiguriert — Nachricht verworfen");
            return DeliveryResult.failed(NOT_CONFIGURED);
        }
        DeliveryResult result = DeliveryResult.ok();
        for (Long chatId : properties.getAllowedChatIds()) {
            result = result.and(sendTo(chatId, text));
        }
        return result;
    }

    public DeliveryResult sendTo(long chatId, String text) {
        if (!properties.isConfigured()) {
            log.debug("Telegram nicht konfiguriert — Nachricht verworfen");
            return DeliveryResult.failed(NOT_CONFIGURED);
        }
        try {
            apiClient.sendMessage(chatId, text);
            return DeliveryResult.ok();
        } catch (Exception ex) {
            log.warn("Telegram-Nachricht an Chat {} fehlgeschlagen: {}", chatId, ex.getMessage());
            return DeliveryResult.failed("Telegram an Chat " + chatId + ": " + ex.getMessage());
        }
    }
}
