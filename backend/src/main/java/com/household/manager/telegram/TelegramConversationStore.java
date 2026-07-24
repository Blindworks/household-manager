package com.household.manager.telegram;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Kurzzeitgedächtnis pro Chat, damit Rückfragen ("und im Schlafzimmer?")
 * funktionieren. Bewusst nur Nutzertext + finale Antwort (keine Tool-Blöcke):
 * klein, und beim Kürzen kann nie eine halbe Tool-Sequenz übrig bleiben.
 * In-memory — ein Neustart vergisst laufende Gespräche, das ist ok.
 */
@Component
public class TelegramConversationStore {

    private record Conversation(List<AnthropicMessage> messages, Instant lastActivity) {
    }

    private final TelegramProperties properties;
    private final Clock clock;
    private final ConcurrentHashMap<Long, Conversation> conversations = new ConcurrentHashMap<>();

    @Autowired
    public TelegramConversationStore(TelegramProperties properties) {
        this(properties, Clock.systemDefaultZone());
    }

    TelegramConversationStore(TelegramProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    public List<AnthropicMessage> history(long chatId) {
        Conversation conversation = conversations.get(chatId);
        if (conversation == null || isExpired(conversation)) {
            conversations.remove(chatId);
            return List.of();
        }
        return List.copyOf(conversation.messages());
    }

    public void appendExchange(long chatId, String userText, String assistantText) {
        List<AnthropicMessage> messages = new ArrayList<>(history(chatId));
        messages.add(AnthropicMessage.user(userText));
        messages.add(new AnthropicMessage("assistant",
                List.of(java.util.Map.of("type", "text", "text", assistantText))));

        int max = properties.getHistoryMaxMessages();
        if (messages.size() > max) {
            messages = new ArrayList<>(messages.subList(messages.size() - max, messages.size()));
        }
        conversations.put(chatId, new Conversation(messages, clock.instant()));
    }

    private boolean isExpired(Conversation conversation) {
        Duration ttl = Duration.ofMinutes(properties.getHistoryTtlMinutes());
        return conversation.lastActivity().plus(ttl).isBefore(clock.instant());
    }
}
