package com.household.manager.telegram;

import com.household.manager.telegram.dto.TelegramUpdate;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Long-Polling gegen die Telegram-Bot-API in einem eigenen Thread
 * ({@code @Scheduled} passt nicht, weil getUpdates bis zu 30 s blockiert).
 * Startet nur, wenn die Integration vollständig konfiguriert ist; bei
 * Telegram-Ausfall Backoff statt Absturz.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TelegramPollingService {

    private final TelegramProperties properties;
    private final TelegramApiClient apiClient;
    private final TelegramAgentService agentService;

    private volatile boolean running;
    private ExecutorService executor;
    private long nextOffset;

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (!properties.isConfigured()) {
            log.info("Telegram-Assistent nicht konfiguriert (Token/Key/Chat-IDs fehlen) — Polling aus");
            return;
        }
        running = true;
        executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "telegram-polling");
            thread.setDaemon(true);
            return thread;
        });
        executor.submit(this::pollLoop);
        log.info("Telegram-Polling gestartet ({} erlaubte Chats)", properties.getAllowedChatIds().size());
    }

    @PreDestroy
    public void stop() {
        running = false;
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    private void pollLoop() {
        while (running) {
            try {
                for (TelegramUpdate update : apiClient.getUpdates(nextOffset, properties.getPollTimeoutSeconds())) {
                    nextOffset = update.updateId() + 1;
                    handleUpdate(update);
                }
            } catch (Exception ex) {
                log.warn("Telegram-Polling gestört, warte {} ms: {}",
                        properties.getErrorBackoffMs(), ex.getMessage());
                sleepBackoff();
            }
        }
    }

    /** Package-private für Tests (Verarbeitung ohne Polling-Thread). */
    void handleUpdate(TelegramUpdate update) {
        if (update.message() == null || update.message().text() == null
                || update.message().chat() == null) {
            return;
        }
        long chatId = update.message().chat().id();
        if (!properties.getAllowedChatIds().contains(chatId)) {
            log.warn("Telegram-Nachricht von nicht erlaubtem Chat {} ignoriert", chatId);
            return;
        }
        String reply = agentService.handleUserMessage(chatId, update.message().text());
        try {
            apiClient.sendMessage(chatId, reply);
        } catch (Exception ex) {
            log.warn("Antwort an Chat {} konnte nicht gesendet werden: {}", chatId, ex.getMessage());
        }
    }

    private void sleepBackoff() {
        try {
            Thread.sleep(properties.getErrorBackoffMs());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
