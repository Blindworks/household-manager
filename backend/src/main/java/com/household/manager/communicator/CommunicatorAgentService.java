package com.household.manager.communicator;

import com.household.manager.ai.AgentScope;
import com.household.manager.ai.AgentToolRegistry;
import com.household.manager.ai.AiProperties;
import com.household.manager.ai.AnthropicApiClient;
import com.household.manager.ai.AnthropicMessage;
import com.household.manager.ai.AnthropicRequest;
import com.household.manager.ai.AnthropicResponse;
import com.household.manager.ai.ToolResult;
import com.household.manager.communicator.CommunicatorDtos.Card;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Der Agent-Loop des Communicators: eine gesprochene Frage hinein, Karten heraus.
 *
 * <p>Das Modell bekommt den Hauszustand im Prompt, die Werkzeuge des Telegram-Dialogs (lesen, schalten,
 * verriegeln), das Sollwert-Werkzeug und die Karten-Werkzeuge {@code show_*}. Besteht eine Antwort nur aus
 * erfolgreichen Karten-Aufrufen, endet der Lauf sofort — der sonst faellige Abschluss-Aufruf kostete eine
 * Sekunde und braechte nichts. Wirft nie: jede Stoerung wird zu einer Text-Karte.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CommunicatorAgentService {

    static final int MAX_CARDS = 3;
    static final String ERROR_TEXT = "Das hat gerade nicht geklappt. Versuch es bitte noch einmal.";
    static final String NOT_CONFIGURED_TEXT = "Die KI-Anbindung ist nicht eingerichtet.";
    static final String DISABLED_TEXT = "Der Communicator ist abgeschaltet.";

    private static final String RULES = """
            Du bist der Communicator des Household Managers: eine Sprach-App auf dem Handy. Der Nutzer \
            spricht eine kurze Frage oder Bitte; als Antwort sieht er grosse Karten, keinen Fliesstext. \
            Die Frage stammt aus einer Spracherkennung und kann kleine Hoerfehler enthalten.

            Regeln:
            - Antworte IMMER mit mindestens einem show_*-Werkzeug und schreibe keinen Text daneben.
            - Hoechstens 3 Karten, die wichtigste zuerst. Meist reicht eine.
            - Nutze IDs ausschliesslich aus dem Hauszustand unten oder aus Werkzeug-Ergebnissen; rate nie.
            - Bittet der Nutzer um eine Aktion (schalten, Modus, Solltemperatur, verriegeln), fuehre sie \
            zuerst mit dem passenden Werkzeug aus und zeige danach die Karte dazu.
            - Gib jeder Karte 2 bis 4 suggestions: naheliegende naechste Schritte passend zu Frage und \
            Zustand. Bei einer Raumtemperatur mit Thermostat z. B. Sollwert ein Grad waermer und kaelter \
            als der aktuelle Sollwert, ein ueblicher Wohlfuehlwert und "Heizung aus" (Sollwert 5). Bei einem \
            Licht das Gegenteil des aktuellen Zustands. Schlage nie den Zustand vor, der schon gilt. \
            Knopftexte hoechstens drei Woerter.
            - Die Haustuer kannst du nur verriegeln; Entriegeln oder Oeffnen lehnst du mit show_info ab.
            - Fuer alles ohne passende Karte: show_info mit hoechstens zwei kurzen Saetzen auf Deutsch.
            - Hast du die Frage nicht verstanden, sag das mit show_info und biete als suggestions (kind ask) \
            zwei, drei Fragen an, die du beantworten kannst.

            Hauszustand:
            """;

    private final CommunicatorProperties properties;
    private final AiProperties aiProperties;
    private final AnthropicApiClient anthropicApiClient;
    private final AgentToolRegistry toolRegistry;
    private final CommunicatorCardFactory cardFactory;
    private final CommunicatorContextBuilder contextBuilder;

    public List<Card> ask(String question) {
        if (!properties.isEnabled()) {
            return List.of(CommunicatorCardFactory.infoCard("block", "Abgeschaltet", DISABLED_TEXT));
        }
        if (!aiProperties.isConfigured()) {
            return List.of(CommunicatorCardFactory.infoCard("error", "Nicht eingerichtet", NOT_CONFIGURED_TEXT));
        }
        List<Card> cards = new ArrayList<>();
        try {
            return run(question, cards);
        } catch (Exception ex) {
            log.error("Communicator-Lauf fehlgeschlagen: {}", ex.getMessage(), ex);
            return cards.isEmpty()
                    ? List.of(CommunicatorCardFactory.infoCard("error", "Fehler", ERROR_TEXT))
                    : cards;
        }
    }

    private List<Card> run(String question, List<Card> cards) {
        List<Map<String, Object>> communicatorTools = toolRegistry.toolDefinitions(AgentScope.COMMUNICATOR);
        Set<String> communicatorToolNames = communicatorTools.stream()
                .map(tool -> String.valueOf(tool.get("name"))).collect(Collectors.toSet());
        List<Map<String, Object>> tools = new ArrayList<>(toolRegistry.toolDefinitions(AgentScope.TELEGRAM));
        tools.addAll(communicatorTools);
        tools.addAll(cardFactory.toolDefinitions());

        String system = RULES + contextBuilder.build();
        List<AnthropicMessage> messages = new ArrayList<>();
        messages.add(AnthropicMessage.user(question));

        for (int iteration = 0; iteration < properties.getMaxToolIterations(); iteration++) {
            AnthropicResponse response = anthropicApiClient.createMessage(AnthropicRequest.of(
                    properties.getModel(), properties.getMaxTokens(), system, messages, tools));

            if (!response.wantsToolUse()) {
                return cards.isEmpty() ? List.of(textFallback(response.text())) : cards;
            }
            ToolRound round = executeTools(response, communicatorToolNames, cards);
            if (round.onlySuccessfulCards() && !cards.isEmpty()) {
                return cards;
            }
            messages.add(AnthropicMessage.assistant(response.content()));
            messages.add(AnthropicMessage.toolResults(round.results()));
        }
        log.warn("Communicator-Lauf nach {} Runden abgebrochen", properties.getMaxToolIterations());
        return cards.isEmpty() ? List.of(CommunicatorCardFactory.infoCard("error", "Fehler", ERROR_TEXT)) : cards;
    }

    private static Card textFallback(String text) {
        return text == null || text.isBlank()
                ? CommunicatorCardFactory.infoCard("error", "Fehler", ERROR_TEXT)
                : CommunicatorCardFactory.infoCard("info", "Antwort", text);
    }

    @SuppressWarnings("unchecked")
    private ToolRound executeTools(AnthropicResponse response, Set<String> communicatorToolNames, List<Card> cards) {
        List<Map<String, Object>> results = new ArrayList<>();
        boolean onlySuccessfulCards = true;
        for (Map<String, Object> block : response.toolUseBlocks()) {
            String toolName = String.valueOf(block.get("name"));
            Map<String, Object> input = block.get("input") instanceof Map<?, ?> map
                    ? (Map<String, Object>) map : Map.of();
            ToolResult result;
            if (cardFactory.handles(toolName)) {
                result = showCard(toolName, input, cards);
                onlySuccessfulCards &= !result.error();
            } else {
                AgentScope scope = communicatorToolNames.contains(toolName)
                        ? AgentScope.COMMUNICATOR : AgentScope.TELEGRAM;
                result = toolRegistry.execute(scope, toolName, input);
                onlySuccessfulCards = false;
            }
            results.add(AnthropicMessage.toolResult(String.valueOf(block.get("id")), result));
        }
        return new ToolRound(results, onlySuccessfulCards);
    }

    private ToolResult showCard(String toolName, Map<String, Object> input, List<Card> cards) {
        if (cards.size() >= MAX_CARDS) {
            return ToolResult.failure("Es werden hoechstens " + MAX_CARDS + " Karten gezeigt.");
        }
        try {
            cards.add(cardFactory.build(toolName, input));
            return ToolResult.ok("Karte wird angezeigt.");
        } catch (Exception ex) {
            log.warn("Karte {} nicht erstellt: {}", toolName, ex.getMessage());
            return ToolResult.failure("Karte nicht erstellt: " + ex.getMessage());
        }
    }

    private record ToolRound(List<Map<String, Object>> results, boolean onlySuccessfulCards) {
    }
}
