package com.household.manager.telegram;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Der Agent-Loop des Telegram-Assistenten: Nutzernachricht + Historie an die
 * Claude API, angefragte Tools ausführen, Ergebnisse zurückschleifen, bis eine
 * finale Antwort steht. Wirft nie — jede Störung wird zu einer freundlichen
 * Chat-Antwort, Details landen im Log.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TelegramAgentService {

    static final String ERROR_REPLY =
            "Ich konnte das gerade nicht verarbeiten. Versuch es bitte gleich noch einmal.";
    static final String LOOP_LIMIT_REPLY =
            "Das war mir zu viele Schritte auf einmal — bitte formuliere es etwas konkreter.";

    private static final String SYSTEM_PROMPT = """
            Du bist der Assistent des Household-Manager-Smart-Home-Systems und antwortest \
            per Telegram. Antworte auf Deutsch, kurz und chat-tauglich (keine Markdown-Tabellen). \
            Nutze die Tools, um Zustände abzufragen und zu schalten; rate nie und behaupte \
            keine Aktion, die kein Tool bestätigt hat. Nenne Geräte bei ihren Anzeigenamen, \
            nicht bei Entity-IDs. Die Haustür kannst du ausschließlich verriegeln — Entriegeln \
            oder Öffnen ist technisch nicht möglich; lehne solche Bitten kurz ab.""";

    private final TelegramProperties properties;
    private final AnthropicApiClient anthropicApiClient;
    private final TelegramToolRegistry toolRegistry;
    private final TelegramConversationStore conversationStore;

    public String handleUserMessage(long chatId, String text) {
        try {
            List<AnthropicMessage> messages = new ArrayList<>(conversationStore.history(chatId));
            messages.add(AnthropicMessage.user(text));

            for (int iteration = 0; iteration < properties.getMaxToolIterations(); iteration++) {
                AnthropicResponse response = anthropicApiClient.createMessage(
                        SYSTEM_PROMPT, messages, toolRegistry.toolDefinitions());

                if (!response.wantsToolUse()) {
                    String answer = response.text();
                    if (answer.isBlank()) {
                        answer = ERROR_REPLY;
                    }
                    conversationStore.appendExchange(chatId, text, answer);
                    return answer;
                }

                messages.add(AnthropicMessage.assistant(response.content()));
                messages.add(AnthropicMessage.toolResults(executeTools(response)));
            }
            log.warn("Agent-Loop für Chat {} nach {} Iterationen abgebrochen",
                    chatId, properties.getMaxToolIterations());
            return LOOP_LIMIT_REPLY;
        } catch (Exception ex) {
            log.error("Telegram-Agent fehlgeschlagen für Chat {}: {}", chatId, ex.getMessage(), ex);
            return ERROR_REPLY;
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> executeTools(AnthropicResponse response) {
        List<Map<String, Object>> results = new ArrayList<>();
        for (Map<String, Object> block : response.toolUseBlocks()) {
            String toolName = String.valueOf(block.get("name"));
            Map<String, Object> input = block.get("input") instanceof Map<?, ?> map
                    ? (Map<String, Object>) map : Map.of();
            ToolResult result = toolRegistry.execute(toolName, input);

            Map<String, Object> resultBlock = new HashMap<>();
            resultBlock.put("type", "tool_result");
            resultBlock.put("tool_use_id", block.get("id"));
            resultBlock.put("content", result.content());
            if (result.error()) {
                resultBlock.put("is_error", true);
            }
            results.add(resultBlock);
        }
        return results;
    }
}
