package com.household.manager.flowengine.nodes;

import com.household.manager.flowengine.FlowMessage;
import com.household.manager.flowengine.NodeContext;
import com.household.manager.flowengine.NodeFieldDescriptor;
import com.household.manager.flowengine.NodeFieldType;
import com.household.manager.flowengine.NodeHandler;
import com.household.manager.flowengine.NodeResult;
import com.household.manager.flowengine.model.NodeConfig;
import com.household.manager.push.PushNotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Aktions-Node: Web-Push-Benachrichtigung an alle abonnierten Geraete (oder
 * nur die eines Nutzers). Platzhalter: {entityId}, {newState}, {oldState}.
 * Sendefehler schluckt der PushNotificationService — der Flow laeuft weiter.
 */
@Component
@RequiredArgsConstructor
public class PushSendNodeHandler implements NodeHandler {

    private static final String DEFAULT_TITLE = "Household Manager";

    private final PushNotificationService notificationService;

    @Override
    public String type() {
        return "push-send";
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
        config.string("userId")
                .map(String::trim)
                .filter(userId -> !userId.isEmpty())
                .ifPresent(userId -> {
                    try {
                        Long.parseLong(userId);
                    } catch (NumberFormatException ex) {
                        errors.add("userId muss numerisch sein");
                    }
                });
        return errors;
    }

    @Override
    public NodeResult handle(FlowMessage message, NodeConfig config, NodeContext ctx) {
        String title = render(config.string("title").filter(s -> !s.isBlank()).orElse(DEFAULT_TITLE), message);
        String body = render(config.string("message").orElse(""), message);
        config.string("userId")
                .map(String::trim)
                .filter(userId -> !userId.isEmpty())
                .ifPresentOrElse(
                        userId -> notificationService.sendToUser(Long.parseLong(userId), title, body),
                        () -> notificationService.sendToAll(title, body));
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
                NodeFieldDescriptor.field("title", "Titel (leer = Household Manager)", NodeFieldType.STRING, false),
                NodeFieldDescriptor.field("userId", "Nutzer-ID (leer = alle Geraete)", NodeFieldType.STRING, false));
    }
}
