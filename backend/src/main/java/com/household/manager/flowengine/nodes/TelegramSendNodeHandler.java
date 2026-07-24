package com.household.manager.flowengine.nodes;

import com.household.manager.flowengine.FlowMessage;
import com.household.manager.flowengine.NodeContext;
import com.household.manager.flowengine.NodeFieldDescriptor;
import com.household.manager.flowengine.NodeFieldType;
import com.household.manager.flowengine.NodeHandler;
import com.household.manager.flowengine.NodeResult;
import com.household.manager.flowengine.model.NodeConfig;
import com.household.manager.telegram.TelegramNotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Aktions-Node: Telegram-Nachricht an die erlaubten Chats (oder einen
 * bestimmten Chat). Platzhalter: {entityId}, {newState}, {oldState}.
 * Sendefehler schluckt der NotificationService — der Flow läuft weiter.
 */
@Component
@RequiredArgsConstructor
public class TelegramSendNodeHandler implements NodeHandler {

    private final TelegramNotificationService notificationService;

    @Override
    public String type() {
        return "telegram-send";
    }

    @Override
    public int outputPorts() {
        return 1;
    }

    @Override
    public List<String> validate(NodeConfig config) {
        List<String> errors = new ArrayList<>();
        if (config.string("message").isEmpty()) {
            errors.add("message fehlt");
        }
        config.string("chatId").ifPresent(chatId -> {
            try {
                Long.parseLong(chatId.trim());
            } catch (NumberFormatException ex) {
                errors.add("chatId muss numerisch sein");
            }
        });
        return errors;
    }

    @Override
    public NodeResult handle(FlowMessage message, NodeConfig config, NodeContext ctx) {
        String text = render(config.string("message").orElse(""), message);
        config.string("chatId")
                .map(String::trim)
                .filter(chatId -> !chatId.isEmpty())
                .ifPresentOrElse(
                        chatId -> notificationService.sendTo(Long.parseLong(chatId), text),
                        () -> notificationService.sendToAllowedChats(text));
        return NodeResult.single(message);
    }

    private String render(String template, FlowMessage message) {
        return template
                .replace("{entityId}", stringValue(message, "entityId"))
                .replace("{newState}", stringValue(message, "newState"))
                .replace("{oldState}", stringValue(message, "oldState"));
    }

    private String stringValue(FlowMessage message, String key) {
        Object value = message.get(key);
        return value != null ? String.valueOf(value) : "";
    }

    @Override
    public List<NodeFieldDescriptor> fields() {
        return List.of(
                NodeFieldDescriptor.field("message", "Nachricht", NodeFieldType.STRING, true),
                NodeFieldDescriptor.field("chatId", "Chat-ID (leer = alle erlaubten)", NodeFieldType.STRING, false));
    }
}
