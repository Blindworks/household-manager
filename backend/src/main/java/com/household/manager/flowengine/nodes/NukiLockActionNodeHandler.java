package com.household.manager.flowengine.nodes;

import com.household.manager.flowengine.FlowMessage;
import com.household.manager.flowengine.NodeContext;
import com.household.manager.flowengine.NodeFieldDescriptor;
import com.household.manager.flowengine.NodeFieldType;
import com.household.manager.flowengine.NodeHandler;
import com.household.manager.flowengine.NodeResult;
import com.household.manager.flowengine.model.NodeConfig;
import com.household.manager.nuki.NukiLockAction;
import com.household.manager.nuki.NukiLockService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Aktions-Node: steuert ein Nuki-Schloss (verriegeln/entsperren/Tür öffnen).
 * smartlockId als String, weil Nuki-IDs den int-Bereich von
 * {@link NodeConfig#integer} überschreiten.
 */
@Component
@RequiredArgsConstructor
public class NukiLockActionNodeHandler implements NodeHandler {

    private static final List<String> ACTIONS = List.of("lock", "unlock", "unlatch");

    private final NukiLockService lockService;

    @Override
    public String type() {
        return "nuki-lock-action";
    }

    @Override
    public int outputPorts() {
        return 1;
    }

    @Override
    public List<String> validate(NodeConfig config) {
        List<String> errors = new ArrayList<>();
        String smartlockId = config.string("smartlockId").orElse(null);
        if (smartlockId == null || !smartlockId.matches("\\d+")) {
            errors.add("smartlockId fehlt oder ist keine Zahl");
        }
        String action = config.string("action").orElse(null);
        if (action == null || !ACTIONS.contains(action)) {
            errors.add("action muss 'lock', 'unlock' oder 'unlatch' sein");
        }
        return errors;
    }

    @Override
    public NodeResult handle(FlowMessage message, NodeConfig config, NodeContext ctx) {
        long smartlockId = Long.parseLong(config.string("smartlockId").orElseThrow());
        NukiLockAction action = NukiLockAction.valueOf(
                config.string("action").orElseThrow().toUpperCase(Locale.ROOT));
        lockService.executeAction(smartlockId, action);
        return NodeResult.single(message);
    }

    @Override
    public List<NodeFieldDescriptor> fields() {
        return List.of(
                NodeFieldDescriptor.field("smartlockId", "Smartlock-ID", NodeFieldType.STRING, true),
                NodeFieldDescriptor.enumField("action", "Aktion", true, ACTIONS));
    }
}
