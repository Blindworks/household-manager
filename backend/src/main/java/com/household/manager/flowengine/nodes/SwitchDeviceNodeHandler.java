package com.household.manager.flowengine.nodes;

import com.household.manager.flowengine.FlowMessage;
import com.household.manager.flowengine.NodeContext;
import com.household.manager.flowengine.NodeHandler;
import com.household.manager.flowengine.NodeResult;
import com.household.manager.flowengine.model.NodeConfig;
import com.household.manager.service.SmartDeviceService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Aktions-Node: schaltet ein SmartDevice (Kasa/Tapo/Meross) ein oder aus.
 * Der resultierende Entity-Zustand kann weitere Flows triggern — gegen
 * Ping-Pong zwischen Flows schützt die rate-limit-Node (Editor-Empfehlung).
 */
@Component
@RequiredArgsConstructor
public class SwitchDeviceNodeHandler implements NodeHandler {

    private final SmartDeviceService smartDeviceService;

    @Override
    public String type() {
        return "switch-device";
    }

    @Override
    public int outputPorts() {
        return 1;
    }

    @Override
    public List<String> validate(NodeConfig config) {
        List<String> errors = new ArrayList<>();
        if (config.integer("deviceId").isEmpty()) {
            errors.add("deviceId fehlt");
        }
        String action = config.string("action").orElse(null);
        if (action == null || (!action.equals("on") && !action.equals("off"))) {
            errors.add("action muss 'on' oder 'off' sein");
        }
        return errors;
    }

    @Override
    public Map<String, String> configSchema() {
        return Map.of(
                "deviceId", "ID des SmartDevice (siehe Geräte-Seite)",
                "action", "'on' oder 'off'");
    }

    @Override
    public NodeResult handle(FlowMessage message, NodeConfig config, NodeContext ctx) {
        long deviceId = config.integer("deviceId").orElseThrow().longValue();
        String action = config.string("action").orElse("on");
        if ("on".equals(action)) {
            smartDeviceService.turnOn(deviceId);
        } else {
            smartDeviceService.turnOff(deviceId);
        }
        return NodeResult.single(message);
    }
}
