package com.household.manager.telegram;

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

    private final TelegramProperties properties;
    private final TelegramApiClient apiClient;

    /** Sendet an alle erlaubten Chats; Fehler einzelner Chats stoppen die anderen nicht. */
    public void sendToAllowedChats(String text) {
        if (!properties.isConfigured()) {
            log.debug("Telegram nicht konfiguriert — Nachricht verworfen");
            return;
        }
        properties.getAllowedChatIds().forEach(chatId -> sendTo(chatId, text));
    }

    public void sendTo(long chatId, String text) {
        if (!properties.isConfigured()) {
            log.debug("Telegram nicht konfiguriert — Nachricht verworfen");
            return;
        }
        try {
            apiClient.sendMessage(chatId, text);
        } catch (Exception ex) {
            log.warn("Telegram-Nachricht an Chat {} fehlgeschlagen: {}", chatId, ex.getMessage());
        }
    }
}
