package com.household.manager.flowengine.nodes;

import com.household.manager.alexa.AlexaTtsMode;
import com.household.manager.flowengine.FlowMessage;
import com.household.manager.flowengine.NodeContext;
import com.household.manager.flowengine.NodeFieldDescriptor;
import com.household.manager.flowengine.NodeFieldType;
import com.household.manager.flowengine.NodeHandler;
import com.household.manager.flowengine.NodeResult;
import com.household.manager.flowengine.model.NodeConfig;
import com.household.manager.service.AlexaAnnouncementService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Aktions-Node: Alexa-Ansage über den bestehenden AlexaAnnouncementService.
 * Platzhalter im Text: {entityId}, {newState}, {oldState}.
 */
@Component
@RequiredArgsConstructor
public class AlexaAnnounceNodeHandler implements NodeHandler {

    private final AlexaAnnouncementService announcementService;

    @Override
    public String type() {
        return "alexa-announce";
    }

    @Override
    public int outputPorts() {
        return 1;
    }

    @Override
    public List<String> validate(NodeConfig config) {
        List<String> errors = new ArrayList<>();
        if (config.string("text").isEmpty()) {
            errors.add("text fehlt");
        }
        String mode = config.string("mode").orElse(null);
        if (mode == null) {
            errors.add("mode fehlt");
        } else {
            try {
                AlexaTtsMode.valueOf(mode);
            } catch (IllegalArgumentException ex) {
                errors.add("mode muss SPEAK oder ANNOUNCE sein");
            }
        }
        if (config.stringList("deviceSerials").isEmpty()) {
            errors.add("deviceSerials fehlt oder ist leer");
        }
        return errors;
    }

    @Override
    public NodeResult handle(FlowMessage message, NodeConfig config, NodeContext ctx) {
        String text = render(config.string("text").orElse(""), message);
        AlexaTtsMode mode = AlexaTtsMode.valueOf(config.string("mode").orElse("ANNOUNCE"));
        announcementService.announce(text, config.stringList("deviceSerials"), mode);
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
                NodeFieldDescriptor.field("text", "Ansagetext", NodeFieldType.STRING, true),
                NodeFieldDescriptor.enumField("mode", "Modus", true, List.of("SPEAK", "ANNOUNCE")),
                NodeFieldDescriptor.field("deviceSerials", "Alexa-Geräte", NodeFieldType.ALEXA_DEVICE_LIST, true));
    }
}
