package com.household.manager.telegram;

import com.household.manager.agent.AgentProposalService;
import com.household.manager.agent.ProposalStatus;
import com.household.manager.ai.AiProperties;
import com.household.manager.systemlog.JobState;
import com.household.manager.systemlog.SystemJobMonitor;
import com.household.manager.telegram.dto.TelegramCallbackQuery;
import com.household.manager.telegram.dto.TelegramUpdate;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.List;
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

    static final int CALLBACK_ANSWER_MAX = 190;
    static final String JOB_KEY = "TelegramPollingService.poll";

    private final TelegramProperties properties;
    private final AiProperties aiProperties;
    private final TelegramApiClient apiClient;
    private final TelegramAgentService agentService;
    private final AgentProposalService proposalService;
    private final SystemJobMonitor jobMonitor;

    private volatile boolean running;
    private ExecutorService executor;
    private long nextOffset;

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (!properties.isConfigured() || !aiProperties.isConfigured()) {
            log.info("Telegram-Assistent nicht konfiguriert (Token/Key/Chat-IDs fehlen) — Polling aus");
            jobMonitor.recordOutcome(JOB_KEY, JobState.INACTIVE, "Telegram nicht konfiguriert");
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
            boolean fetched = false;
            try {
                List<TelegramUpdate> updates = apiClient.getUpdates(nextOffset, properties.getPollTimeoutSeconds());
                fetched = true;
                jobMonitor.recordOutcome(JOB_KEY, JobState.OK, null);
                for (TelegramUpdate update : updates) {
                    nextOffset = update.updateId() + 1;
                    handleUpdate(update);
                }
            } catch (Exception ex) {
                // Nur ein gescheitertes getUpdates ist eine Störung der Anbindung, kein Fehler in handleUpdate.
                if (!fetched) {
                    jobMonitor.recordOutcome(JOB_KEY, JobState.FAILING, ex.getMessage());
                }
                log.warn("Telegram-Polling gestört, warte {} ms: {}",
                        properties.getErrorBackoffMs(), ex.getMessage());
                sleepBackoff();
            }
        }
    }

    /** Package-private für Tests (Verarbeitung ohne Polling-Thread). */
    void handleUpdate(TelegramUpdate update) {
        if (update.callbackQuery() != null) {
            handleCallback(update.callbackQuery());
            return;
        }
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

    /**
     * Knopfdruck unter einem Vorschlag des Hausverstands. Die Allowlist gilt hier genauso wie fuer
     * Texte (Chat aus der Nachricht, an der der Knopf hing) — ein fremder Chat darf nie bis zu
     * {@code decide} kommen. Jeder erlaubte Druck wird per {@code answerCallbackQuery} beantwortet,
     * sonst dreht der Knopf in der App weiter.
     */
    private void handleCallback(TelegramCallbackQuery callback) {
        if (callback.message() == null || callback.message().chat() == null) {
            log.warn("Telegram-Knopfdruck ohne Nachricht/Chat ignoriert");
            return;
        }
        long chatId = callback.message().chat().id();
        if (!properties.getAllowedChatIds().contains(chatId)) {
            log.warn("Telegram-Knopfdruck von nicht erlaubtem Chat {} ignoriert", chatId);
            return;
        }
        var parsed = AgentProposalService.parseCallback(callback.data());
        if (parsed.isEmpty()) {
            answer(callback.id(), "Unbekannter Knopf");
            return;
        }
        String answer;
        try {
            var decided = proposalService.decide(parsed.get().proposalId(), parsed.get().decision(),
                    decidedBy(callback, chatId));
            answer = decided != null && decided.getStatus() == ProposalStatus.FAILED
                    ? abbreviate("Fehlgeschlagen: " + decided.getError(), CALLBACK_ANSWER_MAX)
                    : "Erledigt";
        } catch (IllegalStateException | IllegalArgumentException ex) {
            answer = ex.getMessage() != null ? ex.getMessage() : "Nicht mehr entscheidbar";
        } catch (Exception ex) {
            log.warn("Entscheidung per Telegram-Knopf fehlgeschlagen: {}", ex.getMessage(), ex);
            answer = "Fehler, siehe Admin-Seite";
        }
        answer(callback.id(), answer);
    }

    /** Telegram begrenzt den Text einer Knopf-Antwort auf 200 Zeichen; mit etwas Reserve kuerzen. */
    private static String abbreviate(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max - 1) + "…";
    }

    private String decidedBy(TelegramCallbackQuery callback, long chatId) {
        String name = callback.from() != null ? callback.from().firstName() : null;
        return name != null && !name.isBlank() ? name.trim() : "Chat " + chatId;
    }

    /** Eine gescheiterte Antwort darf die Verarbeitung nicht abbrechen; die Entscheidung ist da schon gefallen. */
    private void answer(String callbackId, String text) {
        try {
            apiClient.answerCallbackQuery(callbackId, text);
        } catch (Exception ex) {
            log.warn("Knopf-Antwort konnte nicht gesendet werden: {}", ex.getMessage());
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
