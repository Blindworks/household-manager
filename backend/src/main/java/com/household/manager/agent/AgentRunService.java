package com.household.manager.agent;

import com.household.manager.ai.AgentScope;
import com.household.manager.ai.AgentToolRegistry;
import com.household.manager.ai.AnthropicApiClient;
import com.household.manager.ai.AnthropicMessage;
import com.household.manager.ai.AnthropicRequest;
import com.household.manager.ai.AnthropicResponse;
import com.household.manager.ai.ToolResult;
import com.household.manager.audit.AuditActor;
import com.household.manager.audit.AuditActorContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Ein Lauf des Hausverstands: Hauszustand als Systemprompt, dann die Tool-Schleife gegen die Claude-API.
 *
 * <p>{@link #run} wirft nie — der Aufrufer ist ein Scheduler, und ein Fehler muss im Protokoll und in
 * der Fehlerserie landen statt den Takt zu töten. Protokoll und Fehlerserie werden deshalb genau einmal
 * je Lauf gerufen, egal wie er endet. Das Zeitbudget wird vor jedem API-Aufruf geprüft (nicht währenddessen);
 * ein Lauf, der Zeit oder Iterationsdeckel reißt, gilt als Fehlschlag, damit hängende Läufe auffallen.
 * Der Audit-Aktor ist während des Laufs {@code AGENT}, damit Aktionen im Audit-Log zuordenbar sind.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AgentRunService {

    static final String TASK_INSTRUCTION =
            "Prüfe, ob etwas zu tun ist. Wenn nein, antworte mit einem Satz Begründung und ohne Tool-Aufruf.";

    /** Statischer Teil des Systemprompts; unverändert, damit das Prompt-Caching der API greift. */
    static final String STATIC_RULES = """
            Du bist der Hausverstand des Household-Manager-Smart-Home-Systems. Du siehst den Zustand des Hauses \
            im Systemprompt (Abschnitt nach diesen Regeln) und handelst ausschließlich über deine Werkzeuge.

            Regeln:
            - Schlage nie etwas vor, das in den letzten 24 Stunden abgelehnt wurde oder noch offen ist.
            - Komfort nur bei Anwesenheit, Sparen bei Abwesenheit.
            - Im Zweifel nichts tun.
            - Sicherheitsrelevante Beobachtungen (Fenster offen bei Regen oder Abwesenheit, Verbraucher läuft \
            seit Stunden bei Abwesenheit) meldest du immer und markierst sie als `urgent`.
            - Nenne Geräte beim Anzeigenamen, nie bei Entity-IDs.
            - Höchstens drei Vorschläge je Lauf.
            - Begründungen schreibst du in einem Satz auf Deutsch.
            - Beobachtungen speicherst du immer mit `expiresInHours`.
            - Notizen mit der Quelle USER sind Vorgaben des Haushalts und binden dich.
            """;

    private final AgentProperties properties;
    private final AnthropicApiClient client;
    private final AgentToolRegistry toolRegistry;
    private final HouseSnapshotBuilder snapshotBuilder;
    private final AgentRunRecorder recorder;
    private final AgentFailureStreak failureStreak;
    private final Clock clock;

    @SuppressWarnings("unchecked")
    public AgentRunResult run(AgentTrigger trigger) {
        LocalDateTime startedAt = LocalDateTime.now(clock);
        long startNanos = System.nanoTime();
        AuditActorContext.set(AuditActor.agent());
        int inputTokens = 0;
        int outputTokens = 0;
        int toolCalls = 0;
        AgentRunResult result = null;
        try {
            String snapshot = snapshotBuilder.build();
            List<Map<String, Object>> system = List.of(
                    AnthropicRequest.cachedBlock(STATIC_RULES), AnthropicRequest.block(snapshot));
            List<AnthropicMessage> messages = new ArrayList<>();
            messages.add(AnthropicMessage.user(trigger.describe() + "\n\n" + TASK_INSTRUCTION));
            List<Map<String, Object>> tools = toolRegistry.toolDefinitions(AgentScope.HOUSE);

            for (int i = 0; i < properties.getMaxToolIterations() && result == null; i++) {
                if (elapsedSeconds(startNanos) > properties.getRunTimeoutSeconds()) {
                    result = failure("Zeitbudget überschritten", inputTokens, outputTokens, toolCalls, startNanos);
                    break;
                }
                AnthropicResponse response = client.createMessage(new AnthropicRequest(
                        properties.getModel(), properties.getMaxTokens(), system, messages, tools));
                inputTokens += response.inputTokens();
                outputTokens += response.outputTokens();
                if (!response.wantsToolUse()) {
                    result = new AgentRunResult(true, response.text(), null,
                            inputTokens, outputTokens, toolCalls, elapsedMs(startNanos));
                    break;
                }
                messages.add(AnthropicMessage.assistant(response.content()));
                List<Map<String, Object>> toolResults = new ArrayList<>();
                for (Map<String, Object> block : response.toolUseBlocks()) {
                    toolCalls++;
                    Map<String, Object> input = block.get("input") instanceof Map<?, ?> map
                            ? (Map<String, Object>) map : Map.of();
                    ToolResult toolResult = toolRegistry.execute(
                            AgentScope.HOUSE, String.valueOf(block.get("name")), input);
                    toolResults.add(AnthropicMessage.toolResult(String.valueOf(block.get("id")), toolResult));
                }
                messages.add(AnthropicMessage.toolResults(toolResults));
            }
            if (result == null) {
                result = failure("Nach " + properties.getMaxToolIterations() + " Iterationen abgebrochen",
                        inputTokens, outputTokens, toolCalls, startNanos);
            }
        } catch (Exception ex) {
            log.warn("Hausverstand-Lauf fehlgeschlagen: {}", ex.getMessage(), ex);
            String reason = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
            result = failure(reason, inputTokens, outputTokens, toolCalls, startNanos);
        } finally {
            AuditActorContext.clear();
        }
        recorder.record(trigger, result, properties.getModel(), startedAt);
        failureStreak.onResult(result);
        return result;
    }

    private AgentRunResult failure(String error, int inputTokens, int outputTokens, int toolCalls, long startNanos) {
        return new AgentRunResult(false, null, error, inputTokens, outputTokens, toolCalls, elapsedMs(startNanos));
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    private static long elapsedSeconds(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000_000L;
    }
}
