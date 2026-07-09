package com.household.manager.flowengine;

import com.household.manager.entitystate.EntityStateService;
import com.household.manager.flowengine.model.FlowDefinition;
import com.household.manager.flowengine.model.FlowNode;
import com.household.manager.flowengine.model.FlowWire;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Validiert eine Flow-Definition beim Deploy: Node-IDs, bekannte Typen,
 * Wire-Referenzen/Ports und die Config jeder Node (delegiert an den Handler).
 * Unbekannte Entitäten sind WARNUNGEN, keine Fehler (Entitäten kommen und gehen).
 */
@Component
public class FlowValidator {

    private final Map<String, NodeHandler> handlersByType = new HashMap<>();
    private final EntityStateService entityStateService;

    public FlowValidator(List<NodeHandler> handlers, EntityStateService entityStateService) {
        for (NodeHandler handler : handlers) {
            handlersByType.put(handler.type(), handler);
        }
        this.entityStateService = entityStateService;
    }

    public ValidationResult validate(FlowDefinition definition) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        Set<String> nodeIds = new HashSet<>();
        for (FlowNode node : definition.nodes()) {
            if (node.id() == null || node.id().isBlank()) {
                errors.add("Node ohne id");
                continue;
            }
            if (!nodeIds.add(node.id())) {
                errors.add("Doppelte Node-id: " + node.id());
            }
            NodeHandler handler = handlersByType.get(node.type());
            if (handler == null) {
                errors.add("Node '" + node.id() + "': unbekannter Typ '" + node.type() + "'");
                continue;
            }
            for (String configError : handler.validate(node.config())) {
                errors.add("Node '" + node.id() + "': " + configError);
            }
            node.config().string("entityId").ifPresent(entityId -> {
                if (entityStateService.getByEntityId(entityId).isEmpty()) {
                    warnings.add("Node '" + node.id() + "': Entität '" + entityId
                            + "' ist (noch) unbekannt — Trigger/Bedingung greift erst, wenn sie existiert");
                }
            });
        }

        for (FlowWire wire : definition.wires()) {
            FlowNode from = findNode(definition, wire.from().node());
            if (from == null) {
                errors.add("Wire von unbekannter Node '" + wire.from().node() + "'");
            } else {
                NodeHandler handler = handlersByType.get(from.type());
                if (handler != null && (wire.from().port() < 0 || wire.from().port() >= handler.outputPorts())) {
                    errors.add("Wire von Node '" + from.id() + "' Port " + wire.from().port()
                            + " existiert nicht (Ports: 0-" + (handler.outputPorts() - 1) + ")");
                }
            }
            if (findNode(definition, wire.to().node()) == null) {
                errors.add("Wire zu unbekannter Node '" + wire.to().node() + "'");
            }
        }

        return new ValidationResult(List.copyOf(errors), List.copyOf(warnings));
    }

    private FlowNode findNode(FlowDefinition definition, String id) {
        return definition.nodes().stream().filter(n -> n.id() != null && n.id().equals(id)).findFirst().orElse(null);
    }
}
