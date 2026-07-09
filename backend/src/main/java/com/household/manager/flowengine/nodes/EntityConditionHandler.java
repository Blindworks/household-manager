package com.household.manager.flowengine.nodes;

import com.household.manager.entitystate.EntityStateService;
import com.household.manager.flowengine.FlowMessage;
import com.household.manager.flowengine.NodeContext;
import com.household.manager.flowengine.NodeHandler;
import com.household.manager.flowengine.NodeResult;
import com.household.manager.flowengine.StateComparator;
import com.household.manager.flowengine.model.NodeConfig;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Prüft den AKTUELLEN Zustand einer beliebigen Entität (Cross-Entity-Bedingung).
 * Port 0 = wahr, Port 1 = falsch. Unbekannte Entität wertet zu falsch.
 */
@Component
@RequiredArgsConstructor
public class EntityConditionHandler implements NodeHandler {

    private final EntityStateService entityStateService;

    @Override
    public String type() {
        return "entity-condition";
    }

    @Override
    public int outputPorts() {
        return 2;
    }

    @Override
    public List<String> validate(NodeConfig config) {
        List<String> errors = new ArrayList<>();
        if (config.string("entityId").isEmpty()) {
            errors.add("entityId fehlt");
        }
        if (config.string("operator").isEmpty()) {
            errors.add("operator fehlt");
        }
        if (config.string("value").isEmpty()) {
            errors.add("value fehlt");
        }
        return errors;
    }

    @Override
    public Map<String, String> configSchema() {
        return Map.of(
                "entityId", "Entity, deren aktueller Zustand geprüft wird",
                "operator", "<, <=, >, >=, == oder !=",
                "value", "Vergleichswert");
    }

    @Override
    public NodeResult handle(FlowMessage message, NodeConfig config, NodeContext ctx) {
        String entityId = config.string("entityId").orElse("");
        String operator = config.string("operator").orElse("");
        String value = config.string("value").orElse("");

        String currentState = entityStateService.getByEntityId(entityId)
                .map(e -> e.getState()).orElse(null);
        boolean matches = StateComparator.matches(currentState, operator, value);
        return NodeResult.port(matches ? 0 : 1, message);
    }
}
